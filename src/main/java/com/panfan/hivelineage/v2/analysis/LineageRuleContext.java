package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LineageRuleContext {
    private final String rulesVersion;
    private final Map<SlotId, ExpressionFacts> inputSlots;
    private final EvidenceFactory evidenceFactory;
    private final List<Dependency> inputRows;
    private final ExpressionResolver expressions;

    interface ExpressionResolver {
        ExpressionFacts resolve(BoundExpression expression, Map<SlotId, ExpressionFacts> slots, List<Dependency> rows);
    }

    public LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots) {
        this(rulesVersion, inputSlots, new FactSupport());
    }

    public LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots, EvidenceFactory evidenceFactory) {
        this(rulesVersion, inputSlots, Collections.emptyList(), evidenceFactory, null);
    }

    LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots, List<Dependency> inputRows,
                       EvidenceFactory evidenceFactory, ExpressionResolver expressions) {
        this.rulesVersion = ModelChecks.text(rulesVersion, "rulesVersion");
        this.inputSlots = ModelCollections.immutableMap(inputSlots);
        this.evidenceFactory = ModelChecks.value(evidenceFactory, "evidenceFactory");
        this.inputRows = ModelCollections.immutableList(inputRows);
        this.expressions = expressions;
    }

    public List<Dependency> getInputRows() {
        return inputRows;
    }

    boolean hasExpressionEnvironment() {
        return expressions != null;
    }

    ExpressionFacts resolve(BoundExpression expression, Map<SlotId, ExpressionFacts> slots, List<Dependency> rows,
                            Map<com.panfan.hivelineage.v2.ir.identity.NodeId, ExpressionFacts> analyzed) {
        if (expressions != null) {
            return expressions.resolve(expression, slots, rows);
        }
        ExpressionFacts facts = analyzed.get(expression.getId());
        if (facts == null) {
            throw new IllegalArgumentException("Missing analyzed expression " + expression.getId().getValue());
        }
        return facts;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }

    public Map<SlotId, ExpressionFacts> getInputSlots() {
        return inputSlots;
    }

    public EvidenceFactory getEvidenceFactory() {
        return evidenceFactory;
    }
}
