package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.frontend.StageOutcome;

import java.util.List;
import java.util.Objects;

public final class ParseResult<P> {
    private final P artifact;
    private final List<Diagnostic> diagnostics;
    private final StageOutcome outcome;

    public ParseResult(P artifact, List<Diagnostic> diagnostics, StageOutcome outcome) {
        if (outcome == StageOutcome.SUCCESS && artifact == null) {
            throw new IllegalArgumentException("successful parsing requires an artifact");
        }
        this.artifact = artifact;
        this.diagnostics = ModelCollections.immutableList(diagnostics);
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public P getArtifact() {
        return artifact;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public StageOutcome getOutcome() {
        return outcome;
    }
}
