package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.analysis.PureIrIndex;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.DefinitionContext;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.panfan.hivelineage.v2.ir.statement.PartitionScope;

final class SessionCatalogConversions {
    private SessionCatalogConversions() {
    }

    static VariableKey variableKey(Effects.SessionKey key) {
        VariableKey.Namespace namespace;
        switch (key.getNamespace()) {
            case HIVEVAR:
                namespace = VariableKey.Namespace.HIVEVAR;
                break;
            case HIVECONF:
                namespace = VariableKey.Namespace.HIVECONF;
                break;
            case SYSTEM_PROPERTY:
                namespace = VariableKey.Namespace.SYSTEM;
                break;
            case ENVIRONMENT:
                namespace = VariableKey.Namespace.ENVIRONMENT;
                break;
            default:
                namespace = VariableKey.Namespace.UNQUALIFIED;
        }
        return new VariableKey(namespace, key.getKey());
    }

    static CatalogEntry entry(Effects.ObjectShape shape, SessionSnapshot session) {
        MetadataProvenance provenance = provenance(session);
        ObjectDescriptor.Kind kind = shape.getKind() == Effects.ObjectKind.TEMPORARY_TABLE ? ObjectDescriptor.Kind.TEMPORARY_TABLE
                : shape.getKind() == Effects.ObjectKind.VIEW ? ObjectDescriptor.Kind.VIEW : ObjectDescriptor.Kind.TABLE;
        ObjectDescriptor object = new ObjectDescriptor(shape.getObjectId(), shape.getName(), kind,
                Optional.<DefinitionRef>empty(), provenance, MetadataVersion.unknown());
        return new CatalogEntry(object, shape.getLookupNames(),
                Optional.of(schema(shape.getSchema(), provenance, shape.getPartitionColumns())), Optional.<LogicalDefinition>empty());
    }

    static RelationSchema schema(OutputSchema schema, MetadataProvenance provenance) {
        return schema(schema, provenance, Collections.<String>emptyList());
    }

    private static RelationSchema schema(OutputSchema schema, MetadataProvenance provenance, List<String> partitions) {
        List<ColumnMetadata> columns = new ArrayList<>();
        for (OutputSegment segment : schema.getSegments()) {
            if (segment instanceof KnownOutputSegment) {
                for (OutputSlot slot : ((KnownOutputSegment) segment).getSlots()) {
                    columns.add(new ColumnMetadata(slot.getDisplayName(), columns.size(), slot.getType(),
                            partitions.contains(slot.getDisplayName()) ? ColumnMetadata.Role.PARTITION : ColumnMetadata.Role.DATA,
                            ColumnMetadata.Nullability.UNKNOWN));
                }
            }
        }
        return new RelationSchema(schema.getState(), columns, provenance, MetadataVersion.unknown());
    }

    static CatalogEntry renamed(CatalogEntry original, QualifiedName name) {
        ObjectDescriptor old = original.getObject();
        ObjectDescriptor replacement = new ObjectDescriptor(old.getObjectId(), name, old.getKind(), old.getDefinition(),
                old.getProvenance(), old.getVersion());
        Set<QualifiedName> lookupNames = new LinkedHashSet<>();
        lookupNames.add(name);
        if (old.getKind() == ObjectDescriptor.Kind.TEMPORARY_TABLE) {
            String simpleName = name.getParts().get(name.getParts().size() - 1);
            for (QualifiedName alias : original.getLookupNames()) {
                List<String> parts = new ArrayList<>(alias.getParts());
                parts.set(parts.size() - 1, simpleName);
                lookupNames.add(new QualifiedName(parts));
            }
        }
        return new CatalogEntry(replacement, new ArrayList<>(lookupNames), original.getSchema(), original.getDefinition());
    }

    static LogicalDefinition definition(Effects.RegisterDefinition effect, SessionEffectPlan plan, SessionSnapshot session) {
        Optional<DefinitionTemplate> template = Optional.ofNullable(plan.getDefinitions().get(effect.getDefinitionId()));
        if (!template.isPresent() && plan.getBoundUnit().isPresent()) {
            com.panfan.hivelineage.v2.ir.relation.BoundRelation relation = plan.getBoundUnit().get().getRelations().stream().filter(node -> node.getId().equals(effect.getRelationRoot())).findFirst().orElse(null);
            if (relation != null) {
                template = Optional.of(new PreparedDefinition(effect.getDefinitionId(), Optional.<com.panfan.hivelineage.v2.syntax.SyntaxTemplate>empty(),
                        Optional.of(relation), SourceRef.synthetic("session:" + effect.getDefinitionId().getValue()), plan.getBoundUnit()));
            }
        }
        InitialSessionContext initial = session.getInitialContext();
        DefinitionContext context = new DefinitionContext(session.getCurrentDatabase(), Collections.<String, String>emptyMap(),
                "hive", "4.0.1", "v2", new MetadataReadContext(initial.getWorkflowId(), initial.getClusterName(), Optional.<String>empty(),
                Collections.<String, String>emptyMap()));
        DefinitionRef reference = new DefinitionRef(effect.getDefinitionId(), Optional.<String>empty(), context, provenance(session),
                MetadataVersion.unknown(), template.isPresent() ? template.get().getSource() : SourceRef.synthetic("session"));
        LogicalDefinition.Kind kind = !effect.getPhysicalObject().isPresent() ? LogicalDefinition.Kind.LEGACY_CTE
                : session.getTemporaryCatalog().containsKey(effect.getName()) || plan.getEffects().stream().anyMatch(proposed -> proposed instanceof Effects.CreateObject
                && ((Effects.CreateObject) proposed).getShape().getName().equals(effect.getName())
                && ((Effects.CreateObject) proposed).getShape().getKind() == Effects.ObjectKind.TEMPORARY_TABLE)
                  ? LogicalDefinition.Kind.TEMPORARY_RELATION : LogicalDefinition.Kind.VIEW;
        return new LogicalDefinition(kind, reference, template);
    }

    static void updateVersions(Map<ObjectId, DataVersionState> versions, SessionEffectPlan plan, Set<ObjectId> uncertainObjects,
                               Set<ObjectId> suppressedVersionWrites) {
        if (!plan.getBoundUnit().isPresent()) {
            return;
        }
        for (BoundWrite write : plan.getBoundUnit().get().getWrites()) {
            BoundObjectReference target = plan.getBoundUnit().get().getObjects().get(write.getTargetObject());
            if (target != null && target.getKind() == BoundObjectReference.ObjectKind.VIEW) {
                continue;
            }
            if (suppressedVersionWrites.contains(write.getTargetObject())) {
                continue;
            }
            if (write.getAfterVersion().getState() == VersionRef.State.NOT_REQUESTED) {
                continue;
            }
            SourceVersions source = sourceVersions(write, plan);
            Set<DatasetVersionId> predecessors = source.ids;
            boolean unknown = source.unknown || uncertainObjects.contains(write.getTargetObject())
                    || write.getAfterVersion().getState() == VersionRef.State.UNKNOWN
                    || write.getPartitionScope().getKind() == PartitionScope.Kind.UNKNOWN;
            boolean replacesWholeDataset = write.getWriteMode() == BoundWrite.WriteMode.CREATE_AS
                    || write.getWriteMode() == BoundWrite.WriteMode.OVERWRITE && write.getPartitionScope().getKind() == PartitionScope.Kind.UNPARTITIONED;
            unknown |= !replacesWholeDataset && write.getBeforeVersion().getState() == VersionRef.State.UNKNOWN;
            if (unknown || !replacesWholeDataset) {
                write.getBeforeVersion().getId().ifPresent(predecessors::add);
            }
            versions.put(write.getTargetObject(), new DataVersionState(unknown ? VersionRef.unknown("DATA_VERSION_UNCERTAIN") : write.getAfterVersion(), new ArrayList<DatasetVersionId>(predecessors),
                    unknown ? DataVersionState.Basis.UNKNOWN : DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS));
        }
    }

    private static SourceVersions sourceVersions(BoundWrite write, SessionEffectPlan plan) {
        PureIrIndex index = new PureIrIndex(plan.getBoundUnit().get());
        Map<NodeId, BoundRelation> relations = new LinkedHashMap<NodeId, BoundRelation>();
        Map<RelationInstanceId, NodeId> occurrences = new LinkedHashMap<RelationInstanceId, NodeId>();
        for (BoundRelation relation : plan.getBoundUnit().get().getRelations()) {
            relations.put(relation.getId(), relation);
            if (relation instanceof Relations.Scan) {
                occurrences.put(((Relations.Scan) relation).getInstanceId(), relation.getId());
            } else if (relation instanceof Relations.DefinitionInstance) {
                occurrences.put(((Relations.DefinitionInstance) relation).getInstanceId(), relation.getId());
            }
        }
        SourceVersions result = new SourceVersions();
        ArrayDeque<NodeId> pending = new ArrayDeque<NodeId>();
        ArrayDeque<BoundExpression> expressions = new ArrayDeque<BoundExpression>();
        write.getAssignments().forEach(assignment -> expressions.add(assignment.getExpression()));
        write.getCondition().ifPresent(expressions::add);
        write.getPartitionScope().getAssignments().forEach(assignment -> expressions.add(assignment.getExpression()));
        write.getSourceRelation().ifPresent(pending::add);
        Set<NodeId> seen = new LinkedHashSet<NodeId>();
        Set<NodeId> visitedExpressions = new LinkedHashSet<NodeId>();
        while (!pending.isEmpty() || !expressions.isEmpty()) {
            while (!expressions.isEmpty()) {
                BoundExpression expression = expressions.removeFirst();
                if (!visitedExpressions.add(expression.getId())) {
                    continue;
                }
                if (expression instanceof Expressions.ScalarSubquery) {
                    pending.add(((Expressions.ScalarSubquery) expression).getQueryRoot());
                } else if (expression instanceof Expressions.ExistsSubquery) {
                    pending.add(((Expressions.ExistsSubquery) expression).getQueryRoot());
                } else if (expression instanceof Expressions.InSubquery) {
                    pending.add(((Expressions.InSubquery) expression).getQueryRoot());
                } else if (expression instanceof Expressions.NotRequestedExpression) {
                    pending.addAll(((Expressions.NotRequestedExpression) expression).getKnownQueryRoots());
                } else if (expression instanceof Expressions.SlotRef) {
                    index.slotOwner(((Expressions.SlotRef) expression).getSlotId()).ifPresent(owner -> pending.add(owner.getId()));
                } else if (expression instanceof Expressions.CorrelatedSlotRef) {
                    index.slotOwner(((Expressions.CorrelatedSlotRef) expression).getSlotId()).ifPresent(owner -> pending.add(owner.getId()));
                } else if (expression instanceof QualifiedFieldHandle) {
                    Optional.ofNullable(occurrences.get(((QualifiedFieldHandle) expression).getOwner())).ifPresent(pending::add);
                } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
                    Optional.ofNullable(occurrences.get(((CorrelatedQualifiedFieldHandle) expression).getOwner())).ifPresent(pending::add);
                }
                expressions.addAll(IrTraversal.children(expression));
            }
            if (pending.isEmpty()) {
                break;
            }
            NodeId id = pending.removeFirst();
            BoundRelation relation = relations.get(id);
            if (!seen.add(id) || relation == null) {
                continue;
            }
            if (relation instanceof Relations.Scan) {
                result.add(((Relations.Scan) relation).getVersion());
            } else if (relation instanceof Relations.DefinitionInstance) {
                Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
                if (definition.getPhysicalObject().isPresent()) {
                    BoundObjectReference object = plan.getBoundUnit().get().getObjects().get(definition.getPhysicalObject().get());
                    if (object == null || object.getKind() != BoundObjectReference.ObjectKind.VIEW) {
                        result.add(definition.getVersion());
                    }
                    if (object != null && object.getKind() == BoundObjectReference.ObjectKind.TEMPORARY) {
                        continue;
                    }
                }
            } else if (relation instanceof ExternalDatasetRelation || relation instanceof Relations.UnknownRelation) {
                result.unknown = true;
            }
            pending.addAll(relation.getInputIds());
            expressions.addAll(IrTraversal.expressions(relation));
        }
        return result;
    }

    private static final class SourceVersions {
        private final Set<DatasetVersionId> ids = new LinkedHashSet<DatasetVersionId>();
        private boolean unknown;

        private void add(VersionRef version) {
            version.getId().ifPresent(ids::add);
            unknown |= version.getState() != VersionRef.State.KNOWN;
        }
    }

    private static MetadataProvenance provenance(SessionSnapshot session) {
        return new MetadataProvenance(session.getInitialContext().getSessionId(), MetadataProvenance.Origin.SESSION_DEFINED,
                Optional.<String>empty(), Optional.<Long>empty());
    }
}
