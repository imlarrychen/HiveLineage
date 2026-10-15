package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.SlotId;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class LineageRuleContext {
    private final String rulesVersion;
    private final Map<SlotId, ExpressionFacts> inputSlots;
    private final EvidenceFactory evidenceFactory;
    private final List<Dependency> inputRows;

    public LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots) {
        this(rulesVersion, inputSlots, new FactSupport());
    }

    public LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots, EvidenceFactory evidenceFactory) {
        this(rulesVersion, inputSlots, Collections.emptyList(), evidenceFactory);
    }

    LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots, List<Dependency> inputRows,
                       EvidenceFactory evidenceFactory) {
        this.rulesVersion = ModelChecks.text(rulesVersion, "rulesVersion");
        this.inputSlots = ModelCollections.immutableMap(inputSlots);
        this.evidenceFactory = ModelChecks.value(evidenceFactory, "evidenceFactory");
        this.inputRows = ModelCollections.immutableList(inputRows);
    }

    public List<Dependency> getInputRows() {
        return inputRows;
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
