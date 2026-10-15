package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates existing identities and scope reachability; never repairs a bound reference. */
public final class DefaultBindingVerifier implements BindingVerifier {
    @Override
    public DiagnosticBag verifyBinding(BoundAnalysisUnit unit, BindingDemand demand) {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        Map<NodeId, BoundRelation> relations = VerificationSupport.relations(unit);
        Map<NodeId, Set<SlotId>> outputs = new LinkedHashMap<>();
        relations.forEach((id, relation) -> outputs.put(id, VerificationSupport.outputs(relation)));
        Map<SlotId, QueryBlockId> slotBlocks = new LinkedHashMap<SlotId, QueryBlockId>();
        Map<RelationInstanceId, Relations.Scan> scans = new LinkedHashMap<RelationInstanceId, Relations.Scan>();
        for (BoundRelation relation : unit.getRelations()) {
            for (SlotId slot : outputs.get(relation.getId())) {
                if (!slotBlocks.containsKey(slot)) {
                    slotBlocks.put(slot, relation.getQueryBlockId());
                }
            }
            if (relation instanceof Relations.Scan) {
                scans.put(((Relations.Scan) relation).getInstanceId(), (Relations.Scan) relation);
            }
        }
        coverage(unit, demand, diagnostics);
        Set<NodeId> requestedOutputs = requestedOutputs(unit, demand, relations);
        for (BoundRelation relation : unit.getRelations()) {
            Set<SlotId> available = new LinkedHashSet<SlotId>();
            Set<RelationInstanceId> owners = new LinkedHashSet<RelationInstanceId>();
            for (NodeId input : relation.getInputIds()) {
                if (relations.containsKey(input)) {
                    available.addAll(outputs.get(input));
                }
                for (NodeId ancestor : VerificationSupport.reachable(input, relations)) {
                    if (relations.get(ancestor) instanceof Relations.Scan) {
                        owners.add(((Relations.Scan) relations.get(ancestor)).getInstanceId());
                    }
                }
            }
            for (BoundExpression expression : IrTraversal.expressions(relation)) {
                for (BoundExpression nested : VerificationSupport.flatten(expression)) {
                    if (nested instanceof Expressions.NotRequestedExpression
                            && demand.getRequirements().contains(((Expressions.NotRequestedExpression) nested).getRequiredFor())) {
                        need(diagnostics, "Requested expression region was not bound", nested);
                    }
                }
                references(expression, relation.getQueryBlockId(), available, owners, slotBlocks, scans, relations, outputs, unit, diagnostics);
            }
            boolean regionRequested = demand.getRegions().isEmpty() || demand.getRegions().contains(unit.getStatementId())
                    || demand.getRegions().contains(relation.getId());
            if (requestedOutputs.contains(relation.getId()) && demand.getRequirements().contains(BindingRequirement.OUTPUT_SCHEMA)
                    && relation.getOutputSchema().getState() == SchemaState.NOT_REQUESTED) {
                need(diagnostics, "Requested output schema was not bound", null);
            }
            if (regionRequested && demand.getRequirements().contains(BindingRequirement.DATASET_VERSIONS)
                    && (relation instanceof Relations.Scan && ((Relations.Scan) relation).getVersion().getState() == VersionRef.State.NOT_REQUESTED
                        || relation instanceof Relations.DefinitionInstance && ((Relations.DefinitionInstance) relation).getPhysicalObject().isPresent()
                            && ((Relations.DefinitionInstance) relation).getVersion().getState() == VersionRef.State.NOT_REQUESTED)) {
                need(diagnostics, "Requested dataset read version was not bound", null);
            }
        }
        Set<SlotId> allOutputs = new LinkedHashSet<SlotId>(slotBlocks.keySet());
        Set<RelationInstanceId> allOwners = new LinkedHashSet<RelationInstanceId>(scans.keySet());
        for (BoundWrite write : unit.getWrites()) {
            Set<SlotId> writeSlots = new LinkedHashSet<SlotId>(allOutputs);
            Set<RelationInstanceId> writeOwners = new LinkedHashSet<RelationInstanceId>(allOwners);
            if (write.getSourceRelation().isPresent()) {
                BoundRelation source = relations.get(write.getSourceRelation().get());
                writeSlots.clear();
                writeOwners.clear();
                if (source != null) {
                    writeSlots.addAll(outputs.get(source.getId()));
                    for (NodeId ancestor : VerificationSupport.reachable(source.getId(), relations)) {
                        if (relations.get(ancestor) instanceof Relations.Scan) {
                            writeOwners.add(((Relations.Scan) relations.get(ancestor)).getInstanceId());
                        }
                    }
                }
            }
            for (WriteAssignment assignment : write.getAssignments()) {
                references(assignment.getExpression(), null, writeSlots, writeOwners, slotBlocks, scans, relations, outputs, unit, diagnostics);
            }
            for (PartitionScope.Assignment assignment : write.getPartitionScope().getAssignments()) {
                references(assignment.getExpression(), null, writeSlots, writeOwners, slotBlocks, scans, relations, outputs, unit, diagnostics);
            }
            write.getCondition().ifPresent(expression -> references(expression, null, writeSlots, writeOwners,
                    slotBlocks, scans, relations, outputs, unit, diagnostics));
            if (demand.getRequirements().contains(BindingRequirement.SINK_MAPPING)
                    && write.getMappingCoverage() == MappingCoverage.NOT_REQUESTED) {
                need(diagnostics, "Requested sink mapping was not bound", null);
            }
            BoundObjectReference target = unit.getObjects().get(write.getTargetObject());
            if (demand.getRequirements().contains(BindingRequirement.DATASET_VERSIONS)
                    && (target == null || target.getKind() != BoundObjectReference.ObjectKind.VIEW)
                    && (write.getBeforeVersion().getState() == VersionRef.State.NOT_REQUESTED
                        || write.getAfterVersion().getState() == VersionRef.State.NOT_REQUESTED)) {
                need(diagnostics, "Requested dataset write versions were not bound", null);
            }
        }
        return new DiagnosticBag(diagnostics);
    }

    private Set<NodeId> requestedOutputs(BoundAnalysisUnit unit, BindingDemand demand, Map<NodeId, BoundRelation> relations) {
        Set<NodeId> result = new LinkedHashSet<NodeId>();
        if (!demand.getRegions().isEmpty() && !demand.getRegions().contains(unit.getStatementId())) {
            result.addAll(demand.getRegions());
            return result;
        }
        for (QueryResult query : unit.getQueryResults()) {
            if (query.getRole() == QueryResult.Role.MAIN) {
                result.add(query.getRelationRoot());
            }
        }
        for (BoundWrite write : unit.getWrites()) {
            boolean referencesOutput = false;
            for (WriteAssignment assignment : write.getAssignments()) {
                referencesOutput |= usesOutputSlot(assignment.getExpression());
            }
            for (PartitionScope.Assignment assignment : write.getPartitionScope().getAssignments()) {
                referencesOutput |= usesOutputSlot(assignment.getExpression());
            }
            if (referencesOutput) {
                write.getSourceRelation().ifPresent(result::add);
            }
        }
        if (result.isEmpty() && unit.getWrites().isEmpty() && unit.getQueryResults().isEmpty()) {
            result.addAll(relations.keySet());
            for (BoundRelation relation : relations.values()) {
                result.removeAll(relation.getInputIds());
                for (BoundExpression expression : IrTraversal.expressions(relation)) {
                    result.removeAll(VerificationSupport.subqueries(expression));
                }
            }
        }
        return result;
    }

    private boolean usesOutputSlot(BoundExpression expression) {
        for (BoundExpression child : VerificationSupport.flatten(expression)) {
            if (child instanceof Expressions.SlotRef) {
                return true;
            }
        }
        return false;
    }

    private void coverage(BoundAnalysisUnit unit, BindingDemand demand, List<Diagnostic> diagnostics) {
        for (BindingRequirement requirement : demand.getRequirements()) {
            if (demand.getRegions().isEmpty()) {
                boolean found = false;
                for (Map<BindingRequirement, BindingCoverage.State> region : unit.getBindingCoverage().getRegions().values()) {
                    BindingCoverage.State state = region.get(requirement);
                    found |= state != null && state != BindingCoverage.State.NOT_REQUESTED;
                }
                if (!found) {
                    need(diagnostics, "Binding coverage is absent for " + requirement, null);
                }
            } else {
                for (NodeId region : demand.getRegions()) {
                    Map<BindingRequirement, BindingCoverage.State> states = unit.getBindingCoverage().getRegions().get(region);
                    if (states == null || !states.containsKey(requirement) || states.get(requirement) == BindingCoverage.State.NOT_REQUESTED) {
                        need(diagnostics, "Region " + region.getValue() + " needs " + requirement, null);
                    }
                }
            }
        }
    }

    private void references(BoundExpression root, QueryBlockId block, Set<SlotId> available,
            Set<RelationInstanceId> owners, Map<SlotId, QueryBlockId> slotBlocks,
            Map<RelationInstanceId, Relations.Scan> scans, Map<NodeId, BoundRelation> relations, Map<NodeId, Set<SlotId>> outputs,
            BoundAnalysisUnit unit, List<Diagnostic> diagnostics) {
        for (BoundExpression expression : VerificationSupport.flatten(root)) {
            if (expression instanceof Expressions.SlotRef) {
                if (!available.contains(((Expressions.SlotRef) expression).getSlotId())) {
                    error(diagnostics, "IR_SLOT_UNREACHABLE", "Slot " + ((Expressions.SlotRef) expression).getSlotId().getValue()
                            + " is not an output of the operator inputs; available=" + available, expression);
                }
            } else if (expression instanceof Expressions.CorrelatedSlotRef) {
                Expressions.CorrelatedSlotRef correlated = (Expressions.CorrelatedSlotRef) expression;
                BoundScopeReference outer = unit.getScopes().get(correlated.getOuterScopeId());
                if (outer == null || block == null || !isAncestor(block, correlated.getOuterScopeId(), unit)
                        || !outer.getQueryBlockId().equals(slotBlocks.get(correlated.getSlotId()))) {
                    error(diagnostics, "IR_CORRELATION_INVALID", "Correlated slot does not belong to a declared ancestor scope", expression);
                }
            } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
                CorrelatedQualifiedFieldHandle field = (CorrelatedQualifiedFieldHandle) expression;
                BoundScopeReference outer = unit.getScopes().get(field.getOuterScopeId());
                Relations.Scan owner = scans.get(field.getOwner());
                if (outer == null || owner == null || block == null || !isAncestor(block, field.getOuterScopeId(), unit)
                        || !outer.getQueryBlockId().equals(owner.getQueryBlockId())) {
                    error(diagnostics, "IR_CORRELATION_INVALID", "Correlated physical owner is not in the declared ancestor scope", expression);
                }
            } else if (expression instanceof QualifiedFieldHandle) {
                QualifiedFieldHandle handle = (QualifiedFieldHandle) expression;
                if (!owners.contains(handle.getOwner()) || !scans.containsKey(handle.getOwner())) {
                    error(diagnostics, "IR_FIELD_OWNER_UNREACHABLE", "Physical field owner is not reachable", expression);
                }
            } else if (expression instanceof Expressions.ScalarSubquery) {
                Expressions.ScalarSubquery subquery = (Expressions.ScalarSubquery) expression;
                subquerySlot(subquery.getQueryRoot(), subquery.getResultSlot(), outputs, diagnostics, expression);
            } else if (expression instanceof Expressions.InSubquery) {
                Expressions.InSubquery subquery = (Expressions.InSubquery) expression;
                subquerySlot(subquery.getQueryRoot(), subquery.getResultSlot(), outputs, diagnostics, expression);
            }
        }
    }

    private void subquerySlot(NodeId root, SlotId slot, Map<NodeId, Set<SlotId>> outputs, List<Diagnostic> diagnostics, BoundExpression expression) {
        if (!outputs.containsKey(root) || !outputs.get(root).contains(slot)) {
            error(diagnostics, "IR_SUBQUERY_SLOT_INVALID", "Subquery result slot is not exported by its root", expression);
        }
    }

    private boolean isAncestor(QueryBlockId block, ScopeId ancestor, BoundAnalysisUnit unit) {
        for (BoundScopeReference scope : unit.getScopes().values()) {
            if (!scope.getQueryBlockId().equals(block)) {
                continue;
            }
            Set<ScopeId> seen = new HashSet<ScopeId>();
            BoundScopeReference cursor = scope;
            while (cursor.getParent().isPresent() && seen.add(cursor.getId())) {
                if (cursor.getParent().get().equals(ancestor)) {
                    return true;
                }
                cursor = unit.getScopes().get(cursor.getParent().get());
                if (cursor == null) {
                    break;
                }
            }
        }
        return false;
    }

    private void need(List<Diagnostic> diagnostics, String message, BoundExpression expression) {
        error(diagnostics, "BINDING_NEEDED", message, expression);
    }

    private void error(List<Diagnostic> diagnostics, String code, String message, BoundExpression expression) {
        VerificationSupport.error(diagnostics, code, message, expression);
    }
}
