package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.metadata.WorkflowContext;

public interface WorkflowBatchAnalyzer {
    WorkflowBatchResult analyze(WorkflowBatchRequest request, WorkflowContext workflow);
}
