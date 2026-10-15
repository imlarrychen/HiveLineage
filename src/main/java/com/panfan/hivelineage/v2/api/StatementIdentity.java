package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class StatementIdentity {
    private final TaskIdentity task;
    private final int statementIndex;
    private final String statementId;

    public StatementIdentity(TaskIdentity task, int statementIndex, String statementId) {
        this.task = ModelChecks.value(task, "task");
        this.statementIndex = ModelChecks.nonNegative(statementIndex, "statementIndex");
        this.statementId = ModelChecks.text(statementId, "statementId");
    }

    public static StatementIdentity forTask(TaskIdentity task, int index) {
        return new StatementIdentity(task, index,
            task.getWorkflowId() + "_" + task.getTaskId() + "_" + task.getTaskVersion() + "_" + index);
    }

    public TaskIdentity getTask() {
        return task;
    }

    public int getStatementIndex() {
        return statementIndex;
    }

    public String getStatementId() {
        return statementId;
    }
}
