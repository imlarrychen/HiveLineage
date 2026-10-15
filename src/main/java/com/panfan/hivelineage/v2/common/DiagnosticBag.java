package com.panfan.hivelineage.v2.common;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DiagnosticBag {
    private final List<Diagnostic> diagnostics;

    public DiagnosticBag(List<Diagnostic> diagnostics) {
        this.diagnostics = ModelCollections.immutableList(diagnostics);
    }

    public static DiagnosticBag empty() {
        return new DiagnosticBag(Collections.<Diagnostic>emptyList());
    }

    /**
     * Keeps first occurrence order at an orchestration boundary.
     */
    public static List<Diagnostic> distinct(List<Diagnostic> diagnostics) {
        Map<DiagnosticKey, Diagnostic> unique = new LinkedHashMap<>();
        diagnostics.forEach(diagnostic -> unique.putIfAbsent(new DiagnosticKey(diagnostic), diagnostic));
        return new ArrayList<>(unique.values());
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
