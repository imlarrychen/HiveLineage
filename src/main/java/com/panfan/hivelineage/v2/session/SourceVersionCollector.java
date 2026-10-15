package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.analysis.PureIrIndex;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One immutable-plan index supports all writes; each collection has an isolated traversal. */
final class SourceVersionCollector {
    private final BoundAnalysisUnit unit;
    private final PureIrIndex index;
    private final Map<NodeId, BoundRelation> relations = new LinkedHashMap<>();
    private final Map<RelationInstanceId, NodeId> occurrences = new LinkedHashMap<>();

    SourceVersionCollector(BoundAnalysisUnit unit) {
        this.unit = unit;
        index = new PureIrIndex(unit);
        for (BoundRelation relation : unit.getRelations()) {
            relations.put(relation.getId(), relation);
            if (relation instanceof Relations.Scan) {
                occurrences.put(((Relations.Scan) relation).getInstanceId(), relation.getId());
            } else if (relation instanceof Relations.DefinitionInstance) {
                occurrences.put(((Relations.DefinitionInstance) relation).getInstanceId(), relation.getId());
            }
        }
    }

    SourceVersions collect(BoundWrite write) {
        Collection traversal = new Collection();
        write.getAssignments().forEach(assignment -> traversal.expressions.add(assignment.getExpression()));
        write.getCondition().ifPresent(traversal.expressions::add);
        write.getPartitionScope().getAssignments().forEach(assignment -> traversal.expressions.add(assignment.getExpression()));
        write.getSourceRelation().ifPresent(traversal.pending::add);
        while (!traversal.pending.isEmpty() || !traversal.expressions.isEmpty()) {
            collectExpressions(traversal);
            if (traversal.pending.isEmpty()) {
                break;
            }
            collectRelation(traversal.pending.removeFirst(), traversal);
        }
        return new SourceVersions(traversal.ids, traversal.unknown);
    }

    private void collectExpressions(Collection traversal) {
        while (!traversal.expressions.isEmpty()) {
            BoundExpression expression = traversal.expressions.removeFirst();
            if (!traversal.visitedExpressions.add(expression.getId())) {
                continue;
            }
            expressionOwners(expression, traversal.pending);
            traversal.expressions.addAll(IrTraversal.children(expression));
        }
    }

    private void expressionOwners(BoundExpression expression, ArrayDeque<NodeId> pending) {
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
    }

    private void collectRelation(NodeId id, Collection traversal) {
        BoundRelation relation = relations.get(id);
        if (!traversal.seen.add(id) || relation == null) {
            return;
        }
        if (relation instanceof Relations.Scan) {
            traversal.add(((Relations.Scan) relation).getVersion());
        } else if (relation instanceof Relations.DefinitionInstance) {
            if (collectDefinition((Relations.DefinitionInstance) relation, traversal)) {
                return;
            }
        } else if (relation instanceof ExternalDatasetRelation || relation instanceof Relations.UnknownRelation) {
            traversal.unknown = true;
        }
        traversal.pending.addAll(relation.getInputIds());
        traversal.expressions.addAll(IrTraversal.expressions(relation));
    }

    private boolean collectDefinition(Relations.DefinitionInstance definition, Collection traversal) {
        if (!definition.getPhysicalObject().isPresent()) {
            return false;
        }
        BoundObjectReference object = unit.getObjects().get(definition.getPhysicalObject().get());
        if (object == null || object.getKind() != BoundObjectReference.ObjectKind.VIEW) {
            traversal.add(definition.getVersion());
        }
        return object != null && object.getKind() == BoundObjectReference.ObjectKind.TEMPORARY;
    }

    static final class SourceVersions {
        private final Set<DatasetVersionId> ids;
        private final boolean unknown;

        SourceVersions(Set<DatasetVersionId> ids, boolean unknown) {
            this.ids = ModelCollections.immutableSet(ids);
            this.unknown = unknown;
        }

        Set<DatasetVersionId> ids() {
            return ids;
        }

        boolean isUnknown() {
            return unknown;
        }
    }

    private static final class Collection {
        private final Set<DatasetVersionId> ids = new LinkedHashSet<>();
        private final ArrayDeque<NodeId> pending = new ArrayDeque<>();
        private final ArrayDeque<BoundExpression> expressions = new ArrayDeque<>();
        private final Set<NodeId> seen = new LinkedHashSet<>();
        private final Set<NodeId> visitedExpressions = new LinkedHashSet<>();
        private boolean unknown;

        private void add(VersionRef version) {
            version.getId().ifPresent(ids::add);
            unknown |= version.getState() != VersionRef.State.KNOWN;
        }
    }
}
