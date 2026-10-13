package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class AnalysisQuality {
    private final Validity validity;
    private final Coverage coverage;
    private final DependencyPrecision precision;
    private final EffectState effectState;

    public AnalysisQuality(Validity validity, Coverage coverage, DependencyPrecision precision, EffectState effectState) {
        this.validity = ModelChecks.value(validity, "validity");
        this.coverage = ModelChecks.value(coverage, "coverage");
        this.precision = ModelChecks.value(precision, "precision");
        this.effectState = ModelChecks.value(effectState, "effectState");
    }

    public Validity getValidity() {
        return validity;
    }

    public Coverage getCoverage() {
        return coverage;
    }

    public DependencyPrecision getPrecision() {
        return precision;
    }

    public EffectState getEffectState() {
        return effectState;
    }
}
