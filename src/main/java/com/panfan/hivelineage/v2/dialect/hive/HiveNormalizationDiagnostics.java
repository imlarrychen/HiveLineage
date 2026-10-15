package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.ArrayList;
import java.util.List;

/** Owns newly emitted normalization diagnostics; callers receive immutable snapshots. */
final class HiveNormalizationDiagnostics {
    private final List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();

    void unsupported(SourceRef source, String reason) {
        diagnostics.add(new Diagnostic(AnalysisStage.NORMALIZATION, "UNSUPPORTED_NATIVE_EXPRESSION", reason, source));
    }

    List<Diagnostic> snapshot() {
        return ModelCollections.immutableList(diagnostics);
    }
}
