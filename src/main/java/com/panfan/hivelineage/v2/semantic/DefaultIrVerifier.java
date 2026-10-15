package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.VerificationCertificate;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Issues a scoped certificate only after both real verifier stages accept the exact unit.
 */
public final class DefaultIrVerifier implements IrVerifier {
    private final StructuralVerifier structural;
    private final BindingVerifier binding;

    public DefaultIrVerifier() {
        this(new DefaultStructuralVerifier(), new DefaultBindingVerifier());
    }

    public DefaultIrVerifier(StructuralVerifier structural, BindingVerifier binding) {
        this.structural = Objects.requireNonNull(structural, "structural");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    @Override
    public VerificationResult verify(BoundAnalysisUnit unit, BindingDemand demand) {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(demand, "demand");
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(structural.verifyStructure(unit).getDiagnostics());
        if (!diagnostics.isEmpty()) {
            return new VerificationResult(VerificationOutcome.REJECTED, Optional.<VerifiedAnalysisUnit>empty(), demand, new DiagnosticBag(diagnostics));
        }
        diagnostics.addAll(binding.verifyBinding(unit, demand).getDiagnostics());
        boolean invalid = false;
        for (Diagnostic diagnostic : diagnostics) {
            invalid |= !"BINDING_NEEDED".equals(diagnostic.getCode());
        }
        if (!diagnostics.isEmpty()) {
            return new VerificationResult(invalid ? VerificationOutcome.REJECTED : VerificationOutcome.NEEDS_BINDING,
                    Optional.<VerifiedAnalysisUnit>empty(), demand, new DiagnosticBag(diagnostics));
        }
        VerificationCertificate certificate = new VerificationCertificate(unit, "default-ir-verifier/1", demand.getRequirements(),
                EnumSet.allOf(VerificationCertificate.VerificationCheck.class), demand.getRegions());
        return new VerificationResult(VerificationOutcome.ACCEPTED, Optional.of(new VerifiedAnalysisUnit(unit, certificate)),
                new BindingDemand(Collections.<BindingRequirement>emptySet(), Collections.<NodeId>emptySet()), DiagnosticBag.empty());
    }
}
