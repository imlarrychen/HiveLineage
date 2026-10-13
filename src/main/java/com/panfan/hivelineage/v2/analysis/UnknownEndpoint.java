package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;

public final class UnknownEndpoint implements LineageEndpoint {
    private final DiagnosticBag diagnostics;

    public UnknownEndpoint(DiagnosticBag diagnostics) {
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.UNKNOWN;
    }
}
