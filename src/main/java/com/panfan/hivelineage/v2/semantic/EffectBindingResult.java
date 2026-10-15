package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;

import java.util.List;

public final class EffectBindingResult {
    private final List<BoundEffect> effects;
    private final DiagnosticBag diagnostics;

    public EffectBindingResult(List<BoundEffect> effects, DiagnosticBag diagnostics) {
        this.effects = ModelCollections.immutableList(effects);
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public List<BoundEffect> getEffects() {
        return effects;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
