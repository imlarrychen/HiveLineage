package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageAnalyzeResult;

public interface HiveLineageAnalyzer {
    LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request);
}
