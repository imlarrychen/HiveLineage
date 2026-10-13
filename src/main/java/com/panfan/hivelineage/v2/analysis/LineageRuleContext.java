package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.Map;

public final class LineageRuleContext {
    private final String rulesVersion;
    private final Map<SlotId, ExpressionFacts> inputSlots;
    private final EvidenceFactory evidenceFactory;

    public LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots) {
        this(rulesVersion, inputSlots, new FactSupport());
    }

    public LineageRuleContext(String rulesVersion, Map<SlotId, ExpressionFacts> inputSlots, EvidenceFactory evidenceFactory) {
        this.rulesVersion = ModelChecks.text(rulesVersion, "rulesVersion");
        this.inputSlots = ModelCollections.immutableMap(inputSlots);
        this.evidenceFactory = ModelChecks.value(evidenceFactory, "evidenceFactory");
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
