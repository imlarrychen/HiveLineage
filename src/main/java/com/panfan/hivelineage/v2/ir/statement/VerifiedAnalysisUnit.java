package com.panfan.hivelineage.v2.ir.statement;

import java.util.Objects;

/**
 * Analysis input carrying an explicit scoped verifier claim; no implicit verify factory.
 */
public final class VerifiedAnalysisUnit {
    private final BoundAnalysisUnit unit;
    private final VerificationCertificate certificate;

    public VerifiedAnalysisUnit(BoundAnalysisUnit unit, VerificationCertificate certificate) {
        this.unit = Objects.requireNonNull(unit, "unit");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        if (certificate.getUnit() != unit) {
            throw new IllegalArgumentException("certificate belongs to a different bound graph");
        }
        if (!certificate.getChecks().contains(VerificationCertificate.VerificationCheck.STRUCTURE)
                || !certificate.getChecks().contains(VerificationCertificate.VerificationCheck.BINDING)) {
            throw new IllegalArgumentException("both structure and binding claims are required");
        }
    }

    public BoundAnalysisUnit getUnit() {
        return unit;
    }

    public VerificationCertificate getCertificate() {
        return certificate;
    }
}
