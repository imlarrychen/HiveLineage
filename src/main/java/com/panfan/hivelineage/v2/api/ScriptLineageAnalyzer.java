package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.metadata.WorkflowContext;

/**
 * Analyzes one SQL script using explicit workflow metadata and session inputs.
 */
public interface ScriptLineageAnalyzer {
    ScriptAnalysisResult analyzeScript(ScriptAnalysisRequest request, WorkflowContext workflow);
}
