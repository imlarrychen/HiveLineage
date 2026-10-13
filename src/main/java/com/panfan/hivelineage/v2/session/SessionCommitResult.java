package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;

/** Result state is always explicit, including retained state after rejection. */
public final class SessionCommitResult {
    private final CommitOutcome outcome;
    private final SessionSnapshot snapshot;
    private final DiagnosticBag diagnostics;

    public SessionCommitResult(CommitOutcome outcome, SessionSnapshot snapshot, DiagnosticBag diagnostics) {
        this.outcome = ModelChecks.value(outcome, "outcome");
        this.snapshot = ModelChecks.value(snapshot, "snapshot");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public CommitOutcome getOutcome() {
        return outcome;
    }

    public SessionSnapshot getSnapshot() {
        return snapshot;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
