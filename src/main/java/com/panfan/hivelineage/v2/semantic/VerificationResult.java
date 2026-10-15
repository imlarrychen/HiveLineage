package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;

import java.util.Optional;

public final class VerificationResult {
    private final VerificationOutcome outcome;
    private final Optional<VerifiedAnalysisUnit> verifiedUnit;
    private final BindingDemand remainingDemand;
    private final DiagnosticBag diagnostics;

    public VerificationResult(VerificationOutcome outcome, Optional<VerifiedAnalysisUnit> verifiedUnit, BindingDemand remainingDemand, DiagnosticBag diagnostics) {
        this.outcome = ModelChecks.value(outcome, "outcome");
        this.verifiedUnit = ModelChecks.value(verifiedUnit, "verifiedUnit");
        this.remainingDemand = ModelChecks.value(remainingDemand, "remainingDemand");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        if ((outcome == VerificationOutcome.ACCEPTED) != verifiedUnit.isPresent()) {
            throw new IllegalArgumentException("only accepted verification carries a certificate");
        }
    }

    public VerificationOutcome getOutcome() {
        return outcome;
    }

    public Optional<VerifiedAnalysisUnit> getVerifiedUnit() {
        return verifiedUnit;
    }

    public BindingDemand getRemainingDemand() {
        return remainingDemand;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
