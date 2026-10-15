package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.SlotId;

import java.util.ArrayList;
import java.util.List;

/**
 * Local operator/cast rule: compose known children without rebinding their identities.
 */
public final class CompositionalExpressionRule implements ExpressionLineageRule {
    private final String ruleId;

    public CompositionalExpressionRule(String ruleId) {
        this.ruleId = java.util.Objects.requireNonNull(ruleId, "ruleId");
    }

    @Override
    public ExpressionFacts analyze(BoundExpression expression, List<ExpressionFacts> arguments, LineageRuleContext context) {
        List<Dependency> dependencies = new ArrayList<Dependency>();
        EvidenceFactory support = context.getEvidenceFactory();
        SlotEndpoint target = new SlotEndpoint(new SlotId("expression:" + expression.getId().getValue()));
        for (ExpressionFacts argument : arguments) {
            dependencies.addAll(support.retarget(argument.getDependencies(), target, expression.getId(), ruleId, null, expression.getSourceRef()));
        }
        return RelationFactsEngine.facts(expression.getId(), dependencies);
    }
}
