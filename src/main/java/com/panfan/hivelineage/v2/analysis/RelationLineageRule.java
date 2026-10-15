package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.List;
import java.util.Map;

public interface RelationLineageRule {
    RelationFacts analyze(BoundRelation relation, List<RelationFacts> inputs, Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context);
}
