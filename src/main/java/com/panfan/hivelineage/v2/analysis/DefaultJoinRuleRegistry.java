package com.panfan.hivelineage.v2.analysis;

import java.util.Optional;

/**
 * Join predicate evaluation uses the same compositional expression rule vocabulary.
 */
public final class DefaultJoinRuleRegistry implements LineageRuleRegistry {
    private final DefaultLineageRuleRegistry delegate = new DefaultLineageRuleRegistry();

    @Override
    public Optional<ExpressionLineageRule> expressionRule(RuleKey key) {
        return delegate.expressionRule(key);
    }

    @Override
    public Optional<RelationLineageRule> relationRule(RuleKey key) {
        return delegate.relationRule(key);
    }
}
