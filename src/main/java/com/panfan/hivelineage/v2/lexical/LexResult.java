package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import java.util.List;
import java.util.Objects;

public final class LexResult {
    private final LexedUnit unit;
    private final List<Diagnostic> diagnostics;
    private final StageOutcome outcome;

    public LexResult(LexedUnit unit, List<Diagnostic> diagnostics, StageOutcome outcome) {
        if (outcome == StageOutcome.SUCCESS && unit == null) {
            throw new IllegalArgumentException("successful lexing requires a unit");
        }
        this.unit = unit;
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public LexedUnit getUnit() {
        return unit;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public StageOutcome getOutcome() {
        return outcome;
    }
}
