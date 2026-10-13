package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.v2.api.V2AnalyzeResult;

public interface LegacyResultProjector {
    LegacyProjection project(V2AnalyzeResult result);
}
