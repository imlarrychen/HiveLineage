package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.TypeRef;

public final class TypeAnalysisResult {
    private final TypeRef type;
    private final boolean complete;
    private final DiagnosticBag diagnostics;

    public TypeAnalysisResult(TypeRef type, boolean complete, DiagnosticBag diagnostics) {
        this.type = ModelChecks.value(type, "type");
        this.complete = complete;
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public TypeRef getType() {
        return type;
    }

    public boolean isComplete() {
        return complete;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
