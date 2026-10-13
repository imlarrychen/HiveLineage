package com.panfan.hivelineage.v2.common;

import java.util.Collections;
import java.util.List;

public final class DiagnosticBag {
    private final List<Diagnostic> diagnostics;

    public DiagnosticBag(List<Diagnostic> diagnostics) {
        this.diagnostics = ModelCollections.immutableList(diagnostics);
    }

    public static DiagnosticBag empty() {
        return new DiagnosticBag(Collections.<Diagnostic>emptyList());
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
