package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class WorkflowBatchRequest {
    private final String workflowId;
    private final List<V2AnalyzeRequest> tasks;

    public WorkflowBatchRequest(String workflowId, List<V2AnalyzeRequest> tasks) {
        this.workflowId = ModelChecks.text(workflowId, "workflowId");
        this.tasks = ModelCollections.immutableList(tasks);
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public List<V2AnalyzeRequest> getTasks() {
        return tasks;
    }
}
