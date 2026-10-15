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
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
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
        VerificationContext context = new VerificationContext(unit);
        Map<NodeId, BoundRelation> relations = context.relations();
        OperatorOutputVerification operators = new OperatorOutputVerification(context, new VerificationDiagnostics(diagnostics));
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
            operators.verify(relation);
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
