package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import java.util.Optional;

public final class SinkPlanningResult {
    private final Optional<BoundWrite> write;
    private final DiagnosticBag diagnostics;

    public SinkPlanningResult(Optional<BoundWrite> write, DiagnosticBag diagnostics) {
        this.write = ModelChecks.value(write, "write");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public Optional<BoundWrite> getWrite() {
        return write;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
