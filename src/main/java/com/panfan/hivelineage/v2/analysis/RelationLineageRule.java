package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.List;
import java.util.Map;

public interface RelationLineageRule {
    default RelationFacts evaluate(BoundRelation relation, List<RelationFacts> inputs, RelationEvaluationContext context) {
        return PreAnalyzedRelationRuleAdapter.evaluate(this, relation, inputs, context);
    }

    RelationFacts analyze(BoundRelation relation, List<RelationFacts> inputs, Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context);
}
