package com.panfan.hivelineage.v2.analysis;

import java.util.Optional;

public interface LineageRuleRegistry {
    Optional<ExpressionLineageRule> expressionRule(RuleKey key);
    Optional<RelationLineageRule> relationRule(RuleKey key);
}
