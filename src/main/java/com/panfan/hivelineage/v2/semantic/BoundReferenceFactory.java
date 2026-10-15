package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BoundReferenceFactory {
    private final BindingWork work;
    private final SourceSchemaLoader schemas;

    BoundReferenceFactory(BindingWork work, SourceSchemaLoader schemas) {
        this.work = work;
        this.schemas = schemas;
    }

    BoundExpression create(NameRefSyntax syntax, ReferenceScope origin, NameLookup.Result result) {
        if (result.kind == NameLookup.Result.Kind.OUTPUT_ALIAS) {
            return alias(syntax, origin, result.aliases);
        }
        NodeId id = work.id(syntax, origin, "field");
        List<String> parts = SemanticNames.parts(syntax.getName());
        if (result.kind != NameLookup.Result.Kind.UNIQUE_SOURCE) {
            return unknown(id, syntax.getSourceRef(), result.candidates, result.diagnostic, parts, false);
        }
        return source(id, syntax.getSourceRef(), parts, origin, result);
    }

    private BoundExpression alias(NameRefSyntax syntax, ReferenceScope scope, List<OutputSlot> aliases) {
        NodeId id = work.id(syntax, scope, "output-alias");
        if (aliases.size() == 1) {
            OutputSlot slot = aliases.get(0);
            return new Expressions.SlotRef(id, slot.getType(), syntax.getSourceRef(), slot.getId(), Collections.<String>emptyList());
        }
        work.warn("AMBIGUOUS_OUTPUT_ALIAS", "Output alias is not unique", syntax.getSourceRef());
        List<SlotId> candidates = new ArrayList<>();
        for (OutputSlot slot : aliases) {
            candidates.add(slot.getId());
        }
        return new Expressions.UnknownReference(id, TypeRef.unknown(), syntax.getSourceRef(), candidates,
                Collections.<RelationInstanceId>emptyList(), "AMBIGUOUS_OUTPUT_ALIAS");
    }

    private BoundExpression source(NodeId id, SourceRef source, List<String> parts,
                                   ReferenceScope origin, NameLookup.Result result) {
        SourceBinding owner = result.candidates.get(0);
        if (!result.path.isEmpty()) {
            schemas.load(owner);
        }
        List<OutputSlot> matches = owner.columns(result.column);
        if (matches.size() == 1) {
            OutputSlot slot = matches.get(0);
            TypeRef type = fieldPathType(slot.getType(), result.path);
            if (type == null) {
                return unknown(id, source, result.candidates, "UNRESOLVED_FIELD_PATH", parts, true);
            }
            if (result.scope != origin) {
                return new Expressions.CorrelatedSlotRef(id, type, source, result.scope.id, slot.getId(), result.path);
            }
            return new Expressions.SlotRef(id, type, source, slot.getId(), result.path);
        }
        if (matches.size() > 1) {
            return unknown(id, source, result.candidates, "AMBIGUOUS_OUTPUT_COLUMN", parts, true);
        }
        if (owner.physical && owner.schema().getState() != SchemaState.KNOWN && result.path.isEmpty()) {
            return physical(id, source, origin, result, owner);
        }
        return unknown(id, source, result.candidates, "UNRESOLVED_COLUMN", parts, true);
    }

    private BoundExpression physical(NodeId id, SourceRef source, ReferenceScope origin,
                                     NameLookup.Result result, SourceBinding owner) {
        FieldIdentity field = work.field(owner.objectId, result.column);
        work.declareField(owner.objectId, field);
        if (result.scope != origin) {
            return new CorrelatedQualifiedFieldHandle(id, TypeRef.unknown(), source, result.scope.id,
                    owner.instanceId, field, result.path, owner.isUncertain() ? CorrelatedQualifiedFieldHandle.ExistenceState.UNKNOWN
                    : CorrelatedQualifiedFieldHandle.ExistenceState.UNVERIFIED);
        }
        return new QualifiedFieldHandle(id, TypeRef.unknown(), source, owner.instanceId, field, result.path,
                owner.isUncertain() ? QualifiedFieldHandle.ExistenceState.UNKNOWN : QualifiedFieldHandle.ExistenceState.UNVERIFIED);
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

    BoundExpression unknown(NodeId id, SourceRef source, List<SourceBinding> candidates, String code) {
        return unknown(id, source, candidates, code, Collections.<String>emptyList(), false);
    }

    BoundExpression unknown(NodeId id, SourceRef source, List<SourceBinding> candidates, String code,
                                    List<String> reference, boolean matchedOwner) {
        List<SlotId> slots = new ArrayList<>();
        List<RelationInstanceId> owners = new ArrayList<>();
        for (SourceBinding candidate : candidates) {
            owners.add(candidate.instanceId);
            for (OutputSlot slot : BindingWork.slots(candidate.schema())) {
                slots.add(slot.getId());
            }
        }
        work.warn(code, "Unable to bind the field uniquely", source);
        RelationInstanceId displayOwner = matchedOwner && owners.size() == 1 ? owners.get(0) : null;
        QualifiedName displayReference = reference.isEmpty() ? null : new QualifiedName(reference);
        return new Expressions.UnknownReference(id, TypeRef.unknown(), source, slots, owners, code, displayOwner, displayReference);
    }

}
