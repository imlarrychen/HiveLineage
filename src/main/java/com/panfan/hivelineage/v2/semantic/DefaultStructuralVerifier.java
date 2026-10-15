package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks graph and operator invariants without recovering names or executing SQL.
 */
public final class DefaultStructuralVerifier implements StructuralVerifier {
    @Override
    public DiagnosticBag verifyStructure(BoundAnalysisUnit unit) {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        Map<NodeId, Object> nodes = new LinkedHashMap<NodeId, Object>();
        Map<NodeId, BoundRelation> relations = VerificationSupport.relations(unit);
        Set<RelationInstanceId> instances = new HashSet<RelationInstanceId>();
        for (BoundRelation relation : unit.getRelations()) {
            declare(nodes, relation.getId(), relation, diagnostics);
            if (!knownRelation(relation)) {
                error(diagnostics, "IR_RELATION_KIND_UNSUPPORTED", "Unregistered relation model cannot be verified");
            }
            if (relation instanceof Relations.Scan) {
                Relations.Scan scan = (Relations.Scan) relation;
                instance(instances, scan.getInstanceId(), diagnostics);
                if (!unit.getObjects().containsKey(scan.getObjectId())) {
                    error(diagnostics, "IR_OBJECT_UNDECLARED", "Scan object is not declared: " + scan.getObjectId().getValue());
                }
            } else if (relation instanceof Relations.DefinitionInstance) {
                Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
                instance(instances, definition.getInstanceId(), diagnostics);
                if (definition.getPhysicalObject().isPresent() && !unit.getObjects().containsKey(definition.getPhysicalObject().get())) {
                    error(diagnostics, "IR_OBJECT_UNDECLARED", "Definition boundary object is not declared");
                }
            }
            for (NodeId input : relation.getInputIds()) {
                if (!relations.containsKey(input)) {
                    error(diagnostics, "IR_INPUT_MISSING", "Missing relation input " + input.getValue());
                }
            }
            for (BoundExpression root : IrTraversal.expressions(relation)) {
                expressionNodes(root, nodes, relations, diagnostics);
            }
            operator(relation, relations, diagnostics);
        }
        Map<NodeId, Integer> colors = new HashMap<NodeId, Integer>();
        for (NodeId id : relations.keySet()) {
            cycle(id, relations, colors, diagnostics);
        }
        slots(unit, relations, diagnostics);
        for (QueryResult result : unit.getQueryResults()) {
            if (!relations.containsKey(result.getRelationRoot())) {
                error(diagnostics, "IR_QUERY_ROOT_MISSING", "Query root is not in graph");
            }
        }
        for (BoundWrite write : unit.getWrites()) {
            declare(nodes, write.getId(), write, diagnostics);
            if (write.getSourceRelation().isPresent() && !relations.containsKey(write.getSourceRelation().get())) {
                error(diagnostics, "IR_WRITE_SOURCE_MISSING", "Write source root is not declared");
            }
            if (!unit.getObjects().containsKey(write.getTargetObject())) {
                error(diagnostics, "IR_OBJECT_UNDECLARED", "Write target is not declared");
            }
            Set<FieldId> targets = new HashSet<FieldId>();
            for (WriteAssignment assignment : write.getAssignments()) {
                if (!targets.add(assignment.getTargetField().getId())) {
                    error(diagnostics, "IR_DUPLICATE_ASSIGNMENT", "Target field assigned twice");
                }
                expressionNodes(assignment.getExpression(), nodes, relations, diagnostics);
            }
            write.getCondition().ifPresent(value -> expressionNodes(value, nodes, relations, diagnostics));
            for (PartitionScope.Assignment assignment : write.getPartitionScope().getAssignments()) {
                expressionNodes(assignment.getExpression(), nodes, relations, diagnostics);
            }
            if (write.getMappingCoverage() == MappingCoverage.COMPLETE && !write.getUnknownMappings().isEmpty()) {
                error(diagnostics, "IR_MAPPING_STATE", "Complete mapping contains unknown intervals");
            }
        }
        for (BoundEffect effect : unit.getEffects()) {
            declare(nodes, effect.getId(), effect, diagnostics);
        }
        scopes(unit, diagnostics);
        return new DiagnosticBag(diagnostics);
    }

    private void scopes(BoundAnalysisUnit unit, List<Diagnostic> diagnostics) {
        for (BoundScopeReference scope : unit.getScopes().values()) {
            Set<ScopeId> ancestors = new HashSet<ScopeId>();
            BoundScopeReference cursor = scope;
            while (cursor.getParent().isPresent()) {
                ScopeId parent = cursor.getParent().get();
                if (!ancestors.add(parent) || parent.equals(scope.getId())) {
                    error(diagnostics, "IR_SCOPE_CYCLE", "Scope ancestry contains a cycle");
                    break;
                }
                if (!unit.getScopes().containsKey(parent)) {
                    error(diagnostics, "IR_SCOPE_PARENT_MISSING", "Scope parent is not declared");
                    break;
                }
                cursor = unit.getScopes().get(parent);
            }
        }
    }

    private void instance(Set<RelationInstanceId> instances, RelationInstanceId id, List<Diagnostic> diagnostics) {
        if (!instances.add(id)) {
            error(diagnostics, "IR_DUPLICATE_INSTANCE", "Scan/definition instance identity is reused");
        }
    }

    private void declare(Map<NodeId, Object> nodes, NodeId id, Object value, List<Diagnostic> diagnostics) {
        if (nodes.containsKey(id) && nodes.get(id) != value) {
            error(diagnostics, "IR_DUPLICATE_NODE", "Different nodes share identity " + id.getValue());
        } else {
            nodes.put(id, value);
        }
    }

    private void expressionNodes(BoundExpression root, Map<NodeId, Object> nodes, Map<NodeId, BoundRelation> relations, List<Diagnostic> diagnostics) {
        for (BoundExpression expression : VerificationSupport.flatten(root)) {
            declare(nodes, expression.getId(), expression, diagnostics);
            if (!knownExpression(expression)) {
                VerificationSupport.error(diagnostics, "IR_EXPRESSION_KIND_UNSUPPORTED", "Unregistered expression model cannot be verified", expression);
            }
        }
        for (NodeId subquery : VerificationSupport.subqueries(root)) {
            if (!relations.containsKey(subquery)) {
                error(diagnostics, "IR_SUBQUERY_MISSING", "Subquery relation root is not declared");
            }
        }
    }

    private void cycle(NodeId id, Map<NodeId, BoundRelation> relations, Map<NodeId, Integer> colors, List<Diagnostic> diagnostics) {
        if (Integer.valueOf(1).equals(colors.get(id))) {
            error(diagnostics, "IR_RELATION_CYCLE", "Relation graph contains a cycle");
            return;
        }
        if (colors.containsKey(id) || !relations.containsKey(id)) {
            return;
        }
        colors.put(id, 1);
        BoundRelation relation = relations.get(id);
        for (NodeId input : relation.getInputIds()) {
            cycle(input, relations, colors, diagnostics);
        }
        for (BoundExpression expression : IrTraversal.expressions(relation)) {
            for (NodeId subquery : VerificationSupport.subqueries(expression)) {
                cycle(subquery, relations, colors, diagnostics);
            }
        }
        colors.put(id, 2);
    }

    private void slots(BoundAnalysisUnit unit, Map<NodeId, BoundRelation> relations, List<Diagnostic> diagnostics) {
        Map<SlotId, BoundRelation> declarations = new LinkedHashMap<SlotId, BoundRelation>();
        List<BoundRelation> ordered = new ArrayList<BoundRelation>();
        Set<NodeId> orderedIds = new HashSet<NodeId>();
        for (NodeId id : relations.keySet()) {
            order(id, relations, orderedIds, ordered);
        }
        for (BoundRelation relation : ordered) {
            // Build once for the operator, rather than once for every repeated output.
            Set<SlotId> transparentReuse = new HashSet<>();
            for (SlotMapping mapping : VerificationSupport.mappings(relation)) {
                if (mapping.getInput().equals(mapping.getOutput())) {
                    transparentReuse.add(mapping.getOutput());
                }
            }
            Set<NodeId> ancestors = null;
            Set<SlotId> local = new HashSet<SlotId>();
            for (OutputSlot slot : IrTraversal.knownSlots(relation.getOutputSchema())) {
                if (!local.add(slot.getId())) {
                    error(diagnostics, "IR_DUPLICATE_OUTPUT_SLOT", "One output repeats a slot identity");
                }
                if (declarations.containsKey(slot.getId())) {
                    if (ancestors == null) {
                        ancestors = new LinkedHashSet<>();
                        for (NodeId input : relation.getInputIds()) {
                            ancestors.addAll(VerificationSupport.reachable(input, relations));
                        }
                    }
                    if (!transparentReuse.contains(slot.getId()) || !ancestors.contains(declarations.get(slot.getId()).getId())) {
                        error(diagnostics, "IR_DUPLICATE_SLOT_DEFINITION", "Output slot is redeclared without transparent reuse");
                    }
                } else {
                    declarations.put(slot.getId(), relation);
                }
            }
        }
    }

    private void order(NodeId id, Map<NodeId, BoundRelation> relations, Set<NodeId> seen, List<BoundRelation> ordered) {
        if (!seen.add(id) || !relations.containsKey(id)) {
            return;
        }
        for (NodeId input : relations.get(id).getInputIds()) {
            order(input, relations, seen, ordered);
        }
        ordered.add(relations.get(id));
    }

    private void operator(BoundRelation relation, Map<NodeId, BoundRelation> relations, List<Diagnostic> diagnostics) {
        Set<SlotId> output = VerificationSupport.outputs(relation);
        Set<SlotId> inputs = new LinkedHashSet<SlotId>();
        for (NodeId input : relation.getInputIds()) {
            if (relations.containsKey(input)) {
                inputs.addAll(VerificationSupport.outputs(relations.get(input)));
            }
        }
        Set<SlotId> produced = new LinkedHashSet<SlotId>();
        for (SlotMapping mapping : VerificationSupport.mappings(relation)) {
            if (!inputs.contains(mapping.getInput()) || !output.contains(mapping.getOutput())) {
                error(diagnostics, "IR_SLOT_MAPPING_INVALID", "Transparent mapping references an absent input/output");
            }
            if (!produced.add(mapping.getOutput())) {
                error(diagnostics, "IR_DUPLICATE_MAPPING", "Output has multiple transparent mappings");
            }
        }
        if (relation instanceof ExternalDatasetRelation) {
            if (!relation.getInputIds().isEmpty() || !output.isEmpty()
                    || relation.getOutputSchema().getState() == SchemaState.KNOWN) {
                error(diagnostics, "IR_EXTERNAL_DATASET_SHAPE", "External storage input must be a leaf with unclaimed output fields");
            }
        } else if (relation instanceof Relations.Values) {
            for (List<BoundExpression> row : ((Relations.Values) relation).getRows()) {
                if (relation.getOutputSchema().getState() == SchemaState.KNOWN && row.size() != output.size()) {
                    error(diagnostics, "IR_VALUES_WIDTH_MISMATCH", "Values row width differs from declared output");
                }
            }
            produced.addAll(output);
        } else if (relation instanceof Relations.Project) {
            assignments(((Relations.Project) relation).getAssignments(), output, produced, diagnostics);
        } else if (relation instanceof Relations.Aggregate) {
            assignments(((Relations.Aggregate) relation).getAssignments(), output, produced, diagnostics);
        } else if (relation instanceof Relations.Window) {
            for (Relations.WindowComputation window : ((Relations.Window) relation).getComputations()) {
                assignments(Collections.singletonList(window.getAssignment()), output, produced, diagnostics);
            }
        } else if (relation instanceof Relations.Generate) {
            for (SlotId slot : ((Relations.Generate) relation).getGeneratedSlots()) {
                if (!output.contains(slot) || !produced.add(slot)) {
                    error(diagnostics, "IR_GENERATE_SLOT_INVALID", "Generated slot absent or duplicated");
                }
            }
        } else if (relation instanceof Relations.SetOp) {
            set((Relations.SetOp) relation, relations, output, produced, diagnostics);
        }
        if (relation instanceof Relations.Join) {
            Relations.Join join = (Relations.Join) relation;
            if ((join.getJoinType() == Relations.JoinType.LEFT_SEMI || join.getJoinType() == Relations.JoinType.LEFT_ANTI)
                    && relations.containsKey(join.getRight())) {
                Set<SlotId> right = VerificationSupport.outputs(relations.get(join.getRight()));
                for (SlotMapping mapping : join.getMappings()) {
                    if (right.contains(mapping.getInput())) {
                        error(diagnostics, "IR_SEMI_JOIN_OUTPUT", "Semi/anti join exposes the right side");
                    }
                }
            }
        }
        if (!(relation instanceof Relations.Scan) && !(relation instanceof Relations.UnknownRelation)
                && relation.getOutputSchema().getState() == SchemaState.KNOWN && !produced.equals(output)) {
            error(diagnostics, "IR_OUTPUT_MAPPING_INCOMPLETE", "Known output is not fully assigned or mapped");
        }
    }

    private void assignments(List<OutputAssignment> assignments, Set<SlotId> output, Set<SlotId> produced, List<Diagnostic> diagnostics) {
        for (OutputAssignment assignment : assignments) {
            if (!output.contains(assignment.getOutput()) || !produced.add(assignment.getOutput())) {
                error(diagnostics, "IR_ASSIGNMENT_INVALID", "Output assignment absent or duplicated");
            }
        }
    }

    private void set(Relations.SetOp set, Map<NodeId, BoundRelation> relations, Set<SlotId> output, Set<SlotId> produced, List<Diagnostic> diagnostics) {
        Integer knownWidth = null;
        for (NodeId branch : set.getBranches()) {
            if (relations.containsKey(branch) && relations.get(branch).getOutputSchema().getState() == SchemaState.KNOWN) {
                int width = VerificationSupport.outputs(relations.get(branch)).size();
                if (knownWidth != null && width != knownWidth) {
                    error(diagnostics, "IR_SET_WIDTH_MISMATCH", "Set branches have different known widths");
                }
                knownWidth = width;
            }
        }
        if (set.getBranches().size() < 2) {
            error(diagnostics, "IR_SET_BRANCH_COUNT", "Set operation needs at least two branches");
        }
        List<OutputSlot> outputs = IrTraversal.knownSlots(set.getOutputSchema());
        Map<NodeId, List<OutputSlot>> branchSlots = new LinkedHashMap<>();
        Map<NodeId, Set<SlotId>> branchOutputs = new LinkedHashMap<>();
        indexSetBranches(set, relations, branchSlots, branchOutputs);
        for (int position = 0; position < set.getMappings().size(); position++) {
            Relations.SetOutputMapping mapping = set.getMappings().get(position);
            if (!output.contains(mapping.getOutput()) || !produced.add(mapping.getOutput())
                    || mapping.getBranchSlots().size() != set.getBranches().size()) {
                error(diagnostics, "IR_SET_MAPPING_INVALID", "Set mapping output/branch count is invalid");
                continue;
            }
            if (set.getOutputSchema().getState() == SchemaState.KNOWN && (position >= outputs.size() || !outputs.get(position).getId().equals(mapping.getOutput()))) {
                error(diagnostics, "IR_SET_POSITION_INVALID", "Set mappings do not follow output order");
            }
            for (int branchIndex = 0; branchIndex < set.getBranches().size(); branchIndex++) {
                BoundRelation branch = relations.get(set.getBranches().get(branchIndex));
                if (branch != null && mapping.getBranchSlots().get(branchIndex).getSlot().isPresent()) {
                    SlotId slot = mapping.getBranchSlots().get(branchIndex).getSlot().get();
                    List<OutputSlot> slots = branchSlots.get(branch.getId());
                    if (!branchOutputs.get(branch.getId()).contains(slot)
                            || branch.getOutputSchema().getState() == SchemaState.KNOWN
                            && (position >= slots.size() || !slots.get(position).getId().equals(slot))) {
                        error(diagnostics, "IR_SET_POSITION_INVALID", "Branch slot does not match positional mapping");
                    }
                }
            }
        }
    }

    private void indexSetBranches(Relations.SetOp set, Map<NodeId, BoundRelation> relations,
                                  Map<NodeId, List<OutputSlot>> branchSlots, Map<NodeId, Set<SlotId>> branchOutputs) {
        for (NodeId id : set.getBranches()) {
            if (relations.containsKey(id)) {
                List<OutputSlot> slots = IrTraversal.knownSlots(relations.get(id).getOutputSchema());
                branchSlots.put(id, slots);
                Set<SlotId> ids = new HashSet<>();
                slots.forEach(slot -> ids.add(slot.getId()));
                branchOutputs.put(id, ids);
            }
        }
    }

    private void error(List<Diagnostic> diagnostics, String code, String message) {
        VerificationSupport.error(diagnostics, code, message, null);
    }

    private boolean knownRelation(BoundRelation relation) {
        return relation instanceof Relations.Scan || relation instanceof Relations.Values || relation instanceof Relations.Project
                || relation instanceof Relations.Filter || relation instanceof Relations.Join || relation instanceof Relations.Aggregate
                || relation instanceof Relations.SetOp || relation instanceof Relations.Window || relation instanceof Relations.Generate
                || relation instanceof Relations.Sort || relation instanceof Relations.Limit || relation instanceof Relations.Distinct
                || relation instanceof Relations.DefinitionInstance || relation instanceof Relations.UnknownRelation
                || relation instanceof ExternalDatasetRelation;
    }

    private boolean knownExpression(BoundExpression expression) {
        return expression instanceof Expressions.Literal || expression instanceof Expressions.SlotRef
                || expression instanceof Expressions.CorrelatedSlotRef || expression instanceof QualifiedFieldHandle
                || expression instanceof CorrelatedQualifiedFieldHandle || expression instanceof Expressions.Call
                || expression instanceof Expressions.Case || expression instanceof Expressions.Cast
                || expression instanceof Expressions.FieldAccess || expression instanceof Expressions.Subscript
                || expression instanceof Expressions.ScalarSubquery || expression instanceof Expressions.ExistsSubquery
                || expression instanceof Expressions.InSubquery || expression instanceof Expressions.UnknownReference
                || expression instanceof Expressions.UnknownExpression || expression instanceof Expressions.NotRequestedExpression;
    }
}
