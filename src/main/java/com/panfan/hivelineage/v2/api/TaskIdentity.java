package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class TaskIdentity {
    private final String workflowId;
    private final String taskId;
    private final String taskVersion;

    public TaskIdentity(String workflowId, String taskId, String taskVersion) {
        this.workflowId = ModelChecks.text(workflowId, "workflowId");
        this.taskId = ModelChecks.text(taskId, "taskId");
        this.taskVersion = ModelChecks.text(taskVersion, "taskVersion");
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskVersion() {
        return taskVersion;
    }
}
