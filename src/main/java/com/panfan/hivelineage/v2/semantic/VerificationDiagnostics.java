package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import java.util.ArrayList;
import java.util.List;

/** Ordered diagnostic sink; rules report failures without changing the IR. */
final class VerificationDiagnostics {
    private final List<Diagnostic> diagnostics;

    VerificationDiagnostics() {
        this(new ArrayList<>());
    }

    VerificationDiagnostics(List<Diagnostic> diagnostics) {
        this.diagnostics = diagnostics;
    }

    void error(String code, String message) {
        error(code, message, null);
    }

    void error(String code, String message, BoundExpression expression) {
        VerificationSupport.error(diagnostics, code, message, expression);
    }

    void need(String message, BoundExpression expression) {
        error("BINDING_NEEDED", message, expression);
    }

    DiagnosticBag result() {
        return new DiagnosticBag(diagnostics);
    }
}
