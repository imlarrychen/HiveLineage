package com.panfan.hivelineage.v2.analysis;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Single registry of built-in operator behavior and stable public evaluation contracts. */
final class BuiltInRelationRules {
    private static final RelationLineageRule UNKNOWN = rule(StandardRelationRules::unknown);
    private static final Map<RuleKey, RelationLineageRule> RULES = rules();

    private BuiltInRelationRules() {
    }

    static Optional<RelationLineageRule> lookup(RuleKey key) {
        return Optional.ofNullable(RULES.get(key));
    }

    static RelationLineageRule unknown() {
        return UNKNOWN;
    }

    private static Map<RuleKey, RelationLineageRule> rules() {
        Map<RuleKey, RelationLineageRule> rules = new LinkedHashMap<RuleKey, RelationLineageRule>();
        rules.put(LineageRuleIdentity.ir("Scan"), rule(StandardRelationRules::scan));
        rules.put(LineageRuleIdentity.ir("Values"), rule(StandardRelationRules::values));
        rules.put(LineageRuleIdentity.ir("Filter"), rule(StandardRelationRules::filter));
        rules.put(LineageRuleIdentity.ir("Join"), rule(StandardRelationRules::join));
        rules.put(LineageRuleIdentity.ir("Aggregate"), rule(StandardRelationRules::aggregate));
        rules.put(LineageRuleIdentity.ir("SetOp"), new SetOperationRelationRule());
        rules.put(LineageRuleIdentity.ir("Window"), new WindowRelationRule());
        rules.put(LineageRuleIdentity.ir("Generate"), new GenerateRelationRule());
        rules.put(LineageRuleIdentity.ir("Sort"), rule(StandardRelationRules::sort));
        rules.put(LineageRuleIdentity.ir("Limit"), rule(StandardRelationRules::limit));
        rules.put(LineageRuleIdentity.ir("Distinct"), rule(StandardRelationRules::distinct));
        rules.put(LineageRuleIdentity.ir("DefinitionInstance"), rule(StandardRelationRules::definitionInstance));
        rules.put(LineageRuleIdentity.ir("UnknownRelation"), UNKNOWN);
        rules.put(LineageRuleIdentity.ir("Project"), new CompositionalProjectRule());
        rules.put(LineageRuleIdentity.ir("ExternalDatasetRelation"), new ExternalDatasetLineageRule());
        return Collections.unmodifiableMap(rules);
    }

    private static RelationLineageRule rule(Consumer<RelationFactBuilder> operation) {
        return (EvaluatingRelationLineageRule) (relation, inputs, context) -> {
            RelationFactBuilder facts = new RelationFactBuilder(relation, context);
            operation.accept(facts);
            return facts.finish();
        };
    }
}
