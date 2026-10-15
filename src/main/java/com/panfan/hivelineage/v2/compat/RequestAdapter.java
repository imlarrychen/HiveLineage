package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;

public interface RequestAdapter {
    ScriptAnalysisRequest adapt(LineageAnalyzeRequest request);
}
