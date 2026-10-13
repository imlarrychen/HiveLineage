package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VerificationSupport {
    private VerificationSupport() {
    }

    static Map<NodeId, BoundRelation> relations(BoundAnalysisUnit unit) {
        Map<NodeId, BoundRelation> result = new LinkedHashMap<NodeId, BoundRelation>();
        for (BoundRelation relation : unit.getRelations()) {
            result.put(relation.getId(), relation);
        }
        return result;
    }

    static List<SlotMapping> mappings(BoundRelation relation) {
        if (relation instanceof Relations.Filter) {
            return ((Relations.Filter) relation).getMappings();
        }
        if (relation instanceof Relations.Join) {
            return ((Relations.Join) relation).getMappings();
        }
        if (relation instanceof Relations.Window) {
            return ((Relations.Window) relation).getRetainedSlots();
        }
        if (relation instanceof Relations.Generate) {
            return ((Relations.Generate) relation).getRetainedSlots();
        }
        if (relation instanceof Relations.Sort) {
            return ((Relations.Sort) relation).getMappings();
        }
        if (relation instanceof Relations.Limit) {
            return ((Relations.Limit) relation).getMappings();
        }
        if (relation instanceof Relations.Distinct) {
            return ((Relations.Distinct) relation).getMappings();
        }
        if (relation instanceof Relations.DefinitionInstance) {
            return ((Relations.DefinitionInstance) relation).getMappings();
        }
        return Collections.emptyList();
    }

    static Set<SlotId> outputs(BoundRelation relation) {
        Set<SlotId> result = new LinkedHashSet<SlotId>();
        for (OutputSlot slot : IrTraversal.knownSlots(relation.getOutputSchema())) {
            result.add(slot.getId());
        }
        return result;
    }

    static Set<NodeId> reachable(NodeId root, Map<NodeId, BoundRelation> relations) {
        Set<NodeId> result = new LinkedHashSet<NodeId>();
        collect(root, relations, result);
        return result;
    }

    private static void collect(NodeId id, Map<NodeId, BoundRelation> relations, Set<NodeId> result) {
        if (!result.add(id) || !relations.containsKey(id)) {
            return;
        }
        for (NodeId input : relations.get(id).getInputIds()) {
            collect(input, relations, result);
        }
    }

    static List<BoundExpression> flatten(BoundExpression root) {
        List<BoundExpression> result = new ArrayList<BoundExpression>();
        flatten(root, result, new HashSet<BoundExpression>());
        return result;
    }

    private static void flatten(BoundExpression expression, List<BoundExpression> result, Set<BoundExpression> seen) {
        if (!seen.add(expression)) {
            return;
        }
        result.add(expression);
        for (BoundExpression child : IrTraversal.children(expression)) {
            flatten(child, result, seen);
        }
    }

    static List<NodeId> subqueries(BoundExpression expression) {
        List<NodeId> result = new ArrayList<NodeId>();
        for (BoundExpression node : flatten(expression)) {
            if (node instanceof Expressions.NotRequestedExpression) {
                result.addAll(((Expressions.NotRequestedExpression) node).getKnownQueryRoots());
            } else if (node instanceof Expressions.ScalarSubquery) {
                result.add(((Expressions.ScalarSubquery) node).getQueryRoot());
            } else if (node instanceof Expressions.ExistsSubquery) {
                result.add(((Expressions.ExistsSubquery) node).getQueryRoot());
            } else if (node instanceof Expressions.InSubquery) {
                result.add(((Expressions.InSubquery) node).getQueryRoot());
            }
        }
        return result;
    }

    static void error(List<Diagnostic> diagnostics, String code, String message, BoundExpression expression) {
        diagnostics.add(new Diagnostic(AnalysisStage.VERIFICATION, code, message,
                expression == null ? null : expression.getSourceRef().orElse(null)));
    }
}
