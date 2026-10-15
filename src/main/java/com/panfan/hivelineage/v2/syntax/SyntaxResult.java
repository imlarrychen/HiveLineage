package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.frontend.StageOutcome;

import java.util.List;
import java.util.Objects;

public final class SyntaxResult {
    private final SyntaxStatement statement;
    private final List<Diagnostic> diagnostics;
    private final StageOutcome outcome;

    public SyntaxResult(SyntaxStatement statement, List<Diagnostic> diagnostics, StageOutcome outcome) {
        if (outcome == StageOutcome.SUCCESS && statement == null) {
            throw new IllegalArgumentException("successful normalization requires a statement");
        }
        this.statement = statement;
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public SyntaxStatement getStatement() {
        return statement;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public StageOutcome getOutcome() {
        return outcome;
    }
}
