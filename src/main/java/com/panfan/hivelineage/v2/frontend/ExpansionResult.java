package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class ExpansionResult {
    private final ExpandedUnit unit;
    private final List<Diagnostic> diagnostics;
    private final StageOutcome outcome;

    public ExpansionResult(ExpandedUnit unit, List<Diagnostic> diagnostics, StageOutcome outcome) {
        if (outcome == StageOutcome.SUCCESS && unit == null) {
            throw new IllegalArgumentException("successful expansion requires a unit");
        }
        this.unit = unit;
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public ExpandedUnit getUnit() {
        return unit;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public StageOutcome getOutcome() {
        return outcome;
    }
}
