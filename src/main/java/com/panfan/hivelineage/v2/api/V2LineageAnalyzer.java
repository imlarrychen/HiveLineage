package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.metadata.WorkflowContext;

/** Interface only; a runnable engine is deliberately not provided yet. */
public interface V2LineageAnalyzer {
    V2AnalyzeResult analyzeScript(V2AnalyzeRequest request, WorkflowContext workflow);
}
