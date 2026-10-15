package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.List;
import java.util.Map;

/**
 * A relation rule that controls expression evaluation, including any refined row environment.
 * The inherited Map entry point remains available for callers with pre-analyzed expressions.
 */
@FunctionalInterface
public interface EvaluatingRelationLineageRule extends RelationLineageRule {
    @Override
    RelationFacts evaluate(BoundRelation relation, List<RelationFacts> inputs, RelationEvaluationContext context);

    @Override
    default RelationFacts analyze(BoundRelation relation, List<RelationFacts> inputs,
                                  Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context) {
        return evaluate(relation, inputs, PreAnalyzedRelationRuleAdapter.context(relation, inputs, expressions, context));
    }
}
