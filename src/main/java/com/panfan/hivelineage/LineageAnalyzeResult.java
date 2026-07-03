package com.panfan.hivelineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LineageAnalyzeResult {
    private final String workflowId;
    private final String taskId;
    private final String taskVersion;
    private final List<StatementLineageResult> statements;
    private final int totalStatementCount;
    private final int ignoredStatementCount;
    private final int failedStatementCount;
    private final List<LineageWarning> warnings;

    public LineageAnalyzeResult(
        String workflowId,
        String taskId,
        String taskVersion,
        List<StatementLineageResult> statements,
        int totalStatementCount,
        int ignoredStatementCount,
        int failedStatementCount
    ) {
        this(
            workflowId,
            taskId,
            taskVersion,
            statements,
            totalStatementCount,
            ignoredStatementCount,
            failedStatementCount,
            collectWarnings(statements)
        );
    }

    public LineageAnalyzeResult(
        String workflowId,
        String taskId,
        String taskVersion,
        List<StatementLineageResult> statements,
        int totalStatementCount,
        int ignoredStatementCount,
        int failedStatementCount,
        List<LineageWarning> warnings
    ) {
        this.workflowId = workflowId;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.statements = Collections.unmodifiableList(new ArrayList<>(statements));
        this.totalStatementCount = totalStatementCount;
        this.ignoredStatementCount = ignoredStatementCount;
        this.failedStatementCount = failedStatementCount;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
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

    public List<StatementLineageResult> getStatements() {
        return statements;
    }

    public int getTotalStatementCount() {
        return totalStatementCount;
    }

    public int getIgnoredStatementCount() {
        return ignoredStatementCount;
    }

    public int getFailedStatementCount() {
        return failedStatementCount;
    }

    public List<LineageWarning> getWarnings() {
        return warnings;
    }

    private static List<LineageWarning> collectWarnings(List<StatementLineageResult> statements) {
        List<LineageWarning> collected = new ArrayList<>();
        for (StatementLineageResult statement : statements) {
            collected.addAll(statement.getWarnings());
        }
        return collected;
    }
}
