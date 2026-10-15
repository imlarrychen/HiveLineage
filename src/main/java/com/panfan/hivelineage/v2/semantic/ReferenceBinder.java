package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.LookupStatus;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ReferenceBinder {
    private final BindingWork work;

    ReferenceBinder(BindingWork work) {
        this.work = work;
    }

    BoundExpression bind(NameRefSyntax syntax, ReferenceScope scope) {
        List<String> parts = SemanticNames.parts(syntax.getName());
        if (parts.size() == 1 && !scope.queryOutputs.isEmpty()) {
            List<OutputSlot> aliases = new ArrayList<>();
            for (OutputSlot slot : scope.queryOutputs) {
                if (SemanticNames.normalize(slot.getDisplayName()).equals(parts.get(0))) {
                    aliases.add(slot);
                }
            }
            if (aliases.size() == 1) {
                OutputSlot slot = aliases.get(0);
                return new Expressions.SlotRef(work.id(syntax, scope, "output-alias"), slot.getType(),
                    syntax.getSourceRef(), slot.getId(), Collections.<String>emptyList());
            }
            if (aliases.size() > 1) {
                work.warn("AMBIGUOUS_OUTPUT_ALIAS", "Output alias is not unique", syntax.getSourceRef());
                List<SlotId> candidates = new ArrayList<>();
                for (OutputSlot slot : aliases) {
                    candidates.add(slot.getId());
                }
                return new Expressions.UnknownReference(work.id(syntax, scope, "output-alias"), TypeRef.unknown(),
                    syntax.getSourceRef(), candidates, Collections.<RelationInstanceId>emptyList(), "AMBIGUOUS_OUTPUT_ALIAS");
            }
        }
        return bind(work.id(syntax, scope, "field"), syntax.getSourceRef(), parts, scope, scope);
    }

    private BoundExpression bind(NodeId id, SourceRef source, List<String> parts,
                                 ReferenceScope current, ReferenceScope origin) {
        List<SourceBinding> owners = new ArrayList<>();
        String column = parts.get(0);
        List<String> path = Collections.emptyList();
        boolean qualified = false;
        if (parts.size() > 1) {
            for (int split = parts.size() - 1; split > 0; split--) {
                String qualifier = String.join(".", parts.subList(0, split));
                List<SourceBinding> explicit = new ArrayList<>();
                for (SourceBinding candidate : current.sources) {
                    if (candidate.aliases.contains(qualifier)) {
                        owners.add(candidate);
                        if (qualifier.equals(candidate.explicitAlias) || !candidate.physical) {
                            explicit.add(candidate);
                        }
                    }
                }
                if (!explicit.isEmpty()) {
                    owners = explicit;
                }
                if (!owners.isEmpty()) {
                    qualified = true;
                    column = parts.get(split);
                    path = new ArrayList<>(parts.subList(split + 1, parts.size()));
                    break;
                }
            }
        }
        if (!qualified) {
            if (parts.size() > 1 && current.parent != null && ancestorAlias(parts, current.parent)) {
                return bind(id, source, parts, current.parent, origin);
            }
            if (parts.size() > 1 && !hasField(current, parts.get(0))) {
                if (current.parent != null) {
                    return bind(id, source, parts, current.parent, origin);
                }
                return unknown(id, source, current.sources, "UNKNOWN_QUALIFIED_OWNER", parts, false);
            }
            column = parts.get(0);
            path = parts.size() > 1 ? new ArrayList<>(parts.subList(1, parts.size())) : Collections.<String>emptyList();
            for (SourceBinding candidate : current.sources) {
                if (current.sources.size() > 1) {
                    loadSchema(candidate, current);
                }
                if (hasColumn(candidate, column) || candidate.schema.getState() == SchemaState.UNKNOWN
                    || (current.sources.size() == 1 && candidate.physical && candidate.schema.getState() != SchemaState.KNOWN)) {
                    owners.add(candidate);
                }
            }
            if (owners.isEmpty() && current.parent != null) {
                return bind(id, source, parts, current.parent, origin);
            }
        }
        if (!qualified && owners.size() > 1) {
            boolean declaredGeneratorField = false;
            for (SourceBinding candidate : owners) {
                declaredGeneratorField |= candidate.generated && hasColumn(candidate, column);
            }
            if (declaredGeneratorField) {
                List<SourceBinding> declared = new ArrayList<>();
                for (SourceBinding candidate : owners) {
                    if (!candidate.physical || hasColumn(candidate, column) || candidate.schema.getState() == SchemaState.KNOWN) {
                        declared.add(candidate);
                    }
                }
                owners = declared;
            }
        }
        if (owners.size() != 1) {
            return unknown(id, source, owners.isEmpty() ? current.sources : owners, "AMBIGUOUS_COLUMN_OWNER", parts, false);
        }
        SourceBinding owner = owners.get(0);
        if (!path.isEmpty()) {
            loadSchema(owner, current);
        }
        List<OutputSlot> matches = new ArrayList<>();
        for (OutputSlot slot : BindingWork.slots(owner.schema)) {
            if (SemanticNames.normalize(slot.getDisplayName()).equals(column)) {
                matches.add(slot);
            }
        }
        if (matches.size() == 1) {
            OutputSlot slot = matches.get(0);
            TypeRef type = fieldPathType(slot.getType(), path);
            if (type == null) {
                return unknown(id, source, owners, "UNRESOLVED_FIELD_PATH", parts, true);
            }
            if (current != origin) {
                return new Expressions.CorrelatedSlotRef(id, type, source, current.id, slot.getId(), path);
            }
            return new Expressions.SlotRef(id, type, source, slot.getId(), path);
        }
        if (matches.size() > 1) {
            return unknown(id, source, owners, "AMBIGUOUS_OUTPUT_COLUMN", parts, true);
        }
        if (owner.physical && owner.schema.getState() != SchemaState.KNOWN && path.isEmpty()) {
            FieldIdentity field = work.field(owner.objectId, column);
            work.declareField(owner.objectId, field);
            if (current != origin) {
                return new CorrelatedQualifiedFieldHandle(id, TypeRef.unknown(), source, current.id,
                    owner.instanceId, field, path, owner.uncertain ? CorrelatedQualifiedFieldHandle.ExistenceState.UNKNOWN
                        : CorrelatedQualifiedFieldHandle.ExistenceState.UNVERIFIED);
            }
            return new QualifiedFieldHandle(id, TypeRef.unknown(), source, owner.instanceId, field, path,
                owner.uncertain ? QualifiedFieldHandle.ExistenceState.UNKNOWN : QualifiedFieldHandle.ExistenceState.UNVERIFIED);
        }
        return unknown(id, source, owners, "UNRESOLVED_COLUMN", parts, true);
    }

    private TypeRef fieldPathType(TypeRef root, List<String> path) {
        TypeRef type = root;
        for (String component : path) {
            if (type.getKind() != TypeRef.Kind.STRUCT) {
                return null;
            }
            TypeRef matched = null;
            for (TypeRef.StructField field : type.getFields()) {
                if (SemanticNames.normalize(field.getName()).equals(SemanticNames.normalize(component))) {
                    matched = field.getType();
                    break;
                }
            }
            if (matched == null) {
                return null;
            }
            type = matched;
        }
        return type;
    }

    private boolean hasField(ReferenceScope scope, String column) {
        for (SourceBinding source : scope.sources) {
            for (OutputSlot slot : BindingWork.slots(source.schema)) {
                if (SemanticNames.normalize(slot.getDisplayName()).equals(column) && slot.getType().getKind() == TypeRef.Kind.STRUCT) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean ancestorAlias(List<String> parts, ReferenceScope scope) {
        for (ReferenceScope current = scope; current != null; current = current.parent) {
            for (SourceBinding source : current.sources) {
                for (int split = parts.size() - 1; split > 0; split--) {
                    if (source.aliases.contains(String.join(".", parts.subList(0, split)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasColumn(SourceBinding source, String column) {
        for (OutputSlot slot : BindingWork.slots(source.schema)) {
            if (SemanticNames.normalize(slot.getDisplayName()).equals(column)) {
                return true;
            }
        }
        return false;
    }

    BoundExpression unknown(NodeId id, SourceRef source, List<SourceBinding> candidates, String code) {
        return unknown(id, source, candidates, code, Collections.<String>emptyList(), false);
    }

    private BoundExpression unknown(NodeId id, SourceRef source, List<SourceBinding> candidates, String code,
                                    List<String> reference, boolean matchedOwner) {
        List<SlotId> slots = new ArrayList<>();
        List<RelationInstanceId> owners = new ArrayList<>();
        for (SourceBinding candidate : candidates) {
            owners.add(candidate.instanceId);
            for (OutputSlot slot : BindingWork.slots(candidate.schema)) {
                slots.add(slot.getId());
            }
        }
        work.warn(code, "Unable to bind the field uniquely", source);
        RelationInstanceId displayOwner = matchedOwner && owners.size() == 1 ? owners.get(0) : null;
        QualifiedName displayReference = reference.isEmpty() ? null : new QualifiedName(reference);
        return new Expressions.UnknownReference(id, TypeRef.unknown(), source, slots, owners, code, displayOwner, displayReference);
    }

    void loadSchema(SourceBinding binding, ReferenceScope scope) {
        if (binding.uncertain) {
            binding.schema = BindingWork.unknown(new NodeId(binding.relationId.getValue() + "/uncertain-width"), "SESSION_OBJECT_UNCERTAIN");
            return;
        }
        if (!binding.physical || binding.schemaAttempted) {
            return;
        }
        binding.schemaAttempted = true;
        com.panfan.hivelineage.v2.session.CatalogEntry local = null;
        for (com.panfan.hivelineage.v2.session.CatalogEntry entry : work.context.getSession().getTemporaryCatalog().values()) {
            if (entry.getObject().getObjectId().equals(binding.objectId)) {
                local = entry;
                break;
            }
        }
        if (local == null) {
            com.panfan.hivelineage.v2.session.OverlayEntry overlay = work.context.getSession().getPersistentOverlay().get(binding.name);
            if (overlay != null && overlay.getState() == com.panfan.hivelineage.v2.session.OverlayEntry.State.DELETED) {
                throw new BindingFailure("OBJECT_NOT_FOUND", "Object was dropped earlier in this script");
            }
            if (overlay != null && overlay.getState() == com.panfan.hivelineage.v2.session.OverlayEntry.State.UNKNOWN) {
                binding.schema = BindingWork.unknown(new NodeId(binding.relationId.getValue() + "/uncertain-width"), "SESSION_OBJECT_UNCERTAIN");
                work.warn("SESSION_OBJECT_UNCERTAIN", "Prior effects left object state uncertain", binding.source);
                return;
            }
            local = overlay == null ? null : overlay.getEntry().orElse(null);
        }
        LookupResult<RelationSchema> result = local == null
            ? work.context.getMetadata().lookupSchema(binding.name, work.context.getMetadataContext())
            : local.getSchema().isPresent() ? LookupResult.found(local.getSchema().get())
                : LookupResult.unknown(com.panfan.hivelineage.v2.common.DiagnosticBag.empty());
        work.diagnostics.addAll(result.getDiagnostics().getDiagnostics());
        if (result.getStatus() != LookupStatus.FOUND) {
            binding.schema = BindingWork.unknown(new NodeId(binding.relationId.getValue() + "/unknown-width"), "METADATA_NOT_FOUND");
            work.warn(result.getStatus() == LookupStatus.ACCESS_ERROR ? "METADATA_ACCESS_ERROR" : "METADATA_NOT_FOUND",
                "Schema is unavailable for " + binding.name.getDisplayName(), binding.source);
        } else {
            List<OutputSlot> slots = new ArrayList<>();
            for (ColumnMetadata column : result.getValue().get().getColumns()) {
                FieldIdentity field = work.field(binding.objectId, column.getName());
                slots.add(new OutputSlot(new SlotId(binding.relationId.getValue() + "/column/" + column.getPosition()),
                    SemanticNames.normalize(column.getName()), column.getType(), field));
            }
            binding.schema = result.getValue().get().getState() == SchemaState.KNOWN ? OutputSchema.known(slots)
                : BindingWork.partialSchema(slots, new NodeId(binding.relationId.getValue() + "/unknown-width"), "SCHEMA_UNKNOWN");
            List<FieldIdentity> fields = new ArrayList<>();
            for (OutputSlot slot : slots) {
                fields.add(slot.getFieldIdentity().get());
            }
            com.panfan.hivelineage.v2.ir.statement.BoundObjectReference object = work.objects.get(binding.objectId);
            work.declare(binding.objectId, binding.name, object.getKind(), result.getValue().get().getState(), fields,
                object.getDefinitionId().orElse(null));
        }
        com.panfan.hivelineage.v2.ir.relation.BoundRelation previous = work.relations.get(binding.relationId);
        if (previous instanceof Relations.Scan) {
            Relations.Scan scan = (Relations.Scan) previous;
            work.put(new Relations.Scan(scan.getId(), binding.schema, scan.getQueryBlockId(), scan.getSourceOrder(),
                scan.getSourceRef().orElse(null), scan.getObjectId(), scan.getVersion(), scan.getInstanceId()));
        }
    }
}
