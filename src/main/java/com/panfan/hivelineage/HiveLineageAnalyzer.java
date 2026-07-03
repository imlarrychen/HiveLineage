package com.panfan.hivelineage;

public interface HiveLineageAnalyzer {
    LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request);
}
