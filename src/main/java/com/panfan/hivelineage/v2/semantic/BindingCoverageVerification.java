package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Demand and coverage checks are independent of reference and version validation. */
final class BindingCoverageVerification {
    private final VerificationContext context;
    private final BindingDemand demand;
    private final VerificationDiagnostics diagnostics;
    private final Set<NodeId> requestedOutputs;

    BindingCoverageVerification(VerificationContext context, BindingDemand demand, VerificationDiagnostics diagnostics) {
        this.context = context;
        this.demand = demand;
        this.diagnostics = diagnostics;
        requestedOutputs = requestedOutputs();
    }

    void expression(BoundExpression expression) {
        for (BoundExpression nested : VerificationSupport.flatten(expression)) {
            if (nested instanceof Expressions.NotRequestedExpression
                    && demand.getRequirements().contains(((Expressions.NotRequestedExpression) nested).getRequiredFor())) {
                diagnostics.need("Requested expression region was not bound", nested);
            }
        }
    }

    void relation(BoundRelation relation) {
        if (requestedOutputs.contains(relation.getId()) && demand.getRequirements().contains(BindingRequirement.OUTPUT_SCHEMA)
                && relation.getOutputSchema().getState() == SchemaState.NOT_REQUESTED) {
            diagnostics.need("Requested output schema was not bound", null);
        }
    }

    void write(BoundWrite write) {
        if (demand.getRequirements().contains(BindingRequirement.SINK_MAPPING)
                && write.getMappingCoverage() == MappingCoverage.NOT_REQUESTED) {
            diagnostics.need("Requested sink mapping was not bound", null);
        }
    }

    private Set<NodeId> requestedOutputs() {
        Set<NodeId> result = new LinkedHashSet<NodeId>();
        if (!demand.getRegions().isEmpty() && !demand.getRegions().contains(context.unit().getStatementId())) {
            result.addAll(demand.getRegions());
            return result;
        }
        for (QueryResult query : context.unit().getQueryResults()) {
            if (query.getRole() == QueryResult.Role.MAIN) {
                result.add(query.getRelationRoot());
            }
        }
        for (BoundWrite write : context.unit().getWrites()) {
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
        if (result.isEmpty() && context.unit().getWrites().isEmpty() && context.unit().getQueryResults().isEmpty()) {
            result.addAll(context.relations().keySet());
            for (BoundRelation relation : context.relations().values()) {
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

    void verifyCoverage() {
        for (BindingRequirement requirement : demand.getRequirements()) {
            if (demand.getRegions().isEmpty()) {
                boolean found = false;
                for (Map<BindingRequirement, BindingCoverage.State> region : context.unit().getBindingCoverage().getRegions().values()) {
                    BindingCoverage.State state = region.get(requirement);
                    found |= state != null && state != BindingCoverage.State.NOT_REQUESTED;
                }
                if (!found) {
                    diagnostics.need("Binding coverage is absent for " + requirement, null);
                }
            } else {
                for (NodeId region : demand.getRegions()) {
                    Map<BindingRequirement, BindingCoverage.State> states = context.unit().getBindingCoverage().getRegions().get(region);
                    if (states == null || !states.containsKey(requirement) || states.get(requirement) == BindingCoverage.State.NOT_REQUESTED) {
                        diagnostics.need("Region " + region.getValue() + " needs " + requirement, null);
                    }
                }
            }
        }
    }

}
