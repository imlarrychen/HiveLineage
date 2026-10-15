package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/** Immutable extensions override the built-in rules in the same registry. */
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
        if (LineageRuleIdentity.VERSION.equals(key.getVersion()) && (LineageRuleIdentity.FUNCTION_NAMESPACE.equals(key.getNamespace()) && key.getOperation().startsWith("operator.")
                || LineageRuleIdentity.IR_NAMESPACE.equals(key.getNamespace()) && "Cast".equals(key.getOperation()))) {
            return Optional.of(new CompositionalExpressionRule(key.getOperation()));
        }
        return Optional.empty();
    }

    @Override
    public Optional<RelationLineageRule> relationRule(RuleKey key) {
        if (relations.containsKey(key)) {
            return Optional.of(relations.get(key));
        }
        return BuiltInRelationRules.lookup(key);
    }
}
