package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class LegacyProjection {
    private final LineageAnalyzeResult result;
    private final List<ProjectionLimitation> limitations;

    public LegacyProjection(LineageAnalyzeResult result, List<ProjectionLimitation> limitations) {
        this.result = ModelChecks.value(result, "result");
        this.limitations = ModelCollections.immutableList(limitations);
    }

    public LineageAnalyzeResult getResult() {
        return result;
    }

    public List<ProjectionLimitation> getLimitations() {
        return limitations;
    }
}
