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
import java.util.ArrayDeque;
import java.util.Collections;
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
        ArrayDeque<NodeId> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            NodeId id = pending.pop();
            if (result.add(id) && relations.containsKey(id)) {
                List<NodeId> inputs = relations.get(id).getInputIds();
                for (int i = inputs.size() - 1; i >= 0; i--) {
                    pending.push(inputs.get(i));
                }
            }
        }
        return result;
    }

    static List<BoundExpression> flatten(BoundExpression root) {
        List<BoundExpression> result = new ArrayList<>();
        Set<BoundExpression> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<BoundExpression, Boolean>());
        ArrayDeque<BoundExpression> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            BoundExpression expression = pending.pop();
            if (!seen.add(expression)) {
                continue;
            }
            result.add(expression);
            List<BoundExpression> children = IrTraversal.children(expression);
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(children.get(i));
            }
        }
        return result;
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
