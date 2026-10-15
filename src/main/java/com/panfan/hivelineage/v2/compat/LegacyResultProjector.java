package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;

public interface LegacyResultProjector {
    LegacyProjection project(ScriptAnalysisResult result);
}
