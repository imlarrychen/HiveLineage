package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/** Immutable explicit extensions; typed built-in rules remain the engine's default. */
public final class DefaultLineageRuleRegistry implements LineageRuleRegistry {
    private final Map<RuleKey, ExpressionLineageRule> expressions;
    private final Map<RuleKey, RelationLineageRule> relations;

    public DefaultLineageRuleRegistry() {
        this(Collections.<RuleKey, ExpressionLineageRule>emptyMap(), Collections.<RuleKey, RelationLineageRule>emptyMap());
    }

    public DefaultLineageRuleRegistry(Map<RuleKey, ExpressionLineageRule> expressions, Map<RuleKey, RelationLineageRule> relations) {
        this.expressions = ModelCollections.immutableMap(expressions);
        this.relations = ModelCollections.immutableMap(relations);
    }

    @Override
    public Optional<ExpressionLineageRule> expressionRule(RuleKey key) {
        if (expressions.containsKey(key)) {
            return Optional.of(expressions.get(key));
        }
        if ("1".equals(key.getVersion()) && ("function".equals(key.getNamespace()) && key.getOperation().startsWith("operator.")
                || "ir".equals(key.getNamespace()) && "Cast".equals(key.getOperation()))) {
            return Optional.of(new CompositionalExpressionRule(key.getOperation()));
        }
        return Optional.empty();
    }

    @Override
    public Optional<RelationLineageRule> relationRule(RuleKey key) {
        if (relations.containsKey(key)) {
            return Optional.of(relations.get(key));
        }
        if ("ir".equals(key.getNamespace()) && "ExternalDatasetRelation".equals(key.getOperation()) && "1".equals(key.getVersion())) {
            return Optional.of(new ExternalDatasetLineageRule());
        }
        if ("ir".equals(key.getNamespace()) && "Project".equals(key.getOperation()) && "1".equals(key.getVersion())) {
            return Optional.of(new CompositionalProjectRule());
        }
        return Optional.empty();
    }
}
