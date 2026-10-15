package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bridges both entry points without requiring the engine to identify a rule implementation. */
final class PreAnalyzedRelationRuleAdapter {
    private PreAnalyzedRelationRuleAdapter() {
    }

    static RelationFacts evaluate(RelationLineageRule rule, BoundRelation relation, List<RelationFacts> inputs,
                                  RelationEvaluationContext context) {
        Map<NodeId, ExpressionFacts> analyzed = new LinkedHashMap<NodeId, ExpressionFacts>();
        for (BoundExpression expression : IrTraversal.expressions(relation)) {
            analyzed.put(expression.getId(), context.resolve(expression));
        }
        return rule.analyze(relation, inputs, analyzed, context.ruleContext());
    }

    static RelationEvaluationContext context(BoundRelation relation, List<RelationFacts> inputs,
                                              Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context) {
        List<Dependency> rows = new ArrayList<Dependency>(context.getInputRows());
        if (rows.isEmpty()) {
            RowSetEndpoint target = new RowSetEndpoint(new RelationInstanceId("rowset:" + relation.getId().getValue()));
            for (RelationFacts input : inputs) {
                rows.addAll(context.getEvidenceFactory().retarget(input.getRowSetFacts(), target, relation.getId(),
                        "relation.rows", null, relation.getSourceRef()));
            }
        }
        return new RelationEvaluationContext(context.getRulesVersion(), context.getInputSlots(), rows,
                context.getEvidenceFactory(), (expression, slots, environment) -> required(expressions, expression));
    }

    private static ExpressionFacts required(Map<NodeId, ExpressionFacts> expressions, BoundExpression expression) {
        ExpressionFacts facts = expressions.get(expression.getId());
        if (facts == null) {
            throw new IllegalArgumentException("Missing analyzed expression " + expression.getId().getValue());
        }
        return facts;
    }
}
