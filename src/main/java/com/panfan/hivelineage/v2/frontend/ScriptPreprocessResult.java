package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class ScriptPreprocessResult {
    private final List<ScriptUnit> units;
    private final List<Diagnostic> diagnostics;
    private final StageOutcome outcome;

    public ScriptPreprocessResult(List<ScriptUnit> units, List<Diagnostic> diagnostics, StageOutcome outcome) {
        this.units = ModelCollections.immutableList(units);
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public List<ScriptUnit> getUnits() {
        return units;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public StageOutcome getOutcome() {
        return outcome;
    }
}
