package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.common.ModelChecks;

public final class ComparisonResult {
    private final LineageAnalyzeResult v1;
    private final ScriptAnalysisResult v2;
    private final LineageDifferenceReport report;

    public ComparisonResult(LineageAnalyzeResult v1, ScriptAnalysisResult v2, LineageDifferenceReport report) {
        this.v1 = ModelChecks.value(v1, "v1");
        this.v2 = ModelChecks.value(v2, "v2");
        this.report = ModelChecks.value(report, "report");
    }

    public LineageAnalyzeResult getV1() {
        return v1;
    }

    public ScriptAnalysisResult getV2() {
        return v2;
    }

    public LineageDifferenceReport getReport() {
        return report;
    }
}
