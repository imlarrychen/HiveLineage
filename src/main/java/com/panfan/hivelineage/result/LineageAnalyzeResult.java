package com.panfan.hivelineage.result;

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

    private LineageAnalyzeResult(Builder builder) {
        this.workflowId = builder.workflowId;
        this.taskId = builder.taskId;
        this.taskVersion = builder.taskVersion;
        this.statements = Collections.unmodifiableList(new ArrayList<>(builder.statements));
        this.totalStatementCount = builder.totalStatementCount;
        this.ignoredStatementCount = builder.ignoredStatementCount;
        this.failedStatementCount = builder.failedStatementCount;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(builder.resolveWarnings()));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String workflowId;
        private String taskId;
        private String taskVersion;
        private List<StatementLineageResult> statements = Collections.emptyList();
        private int totalStatementCount;
        private int ignoredStatementCount;
        private int failedStatementCount;
        private List<LineageWarning> warnings;

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskVersion(String taskVersion) {
            this.taskVersion = taskVersion;
            return this;
        }

        public Builder statements(List<StatementLineageResult> statements) {
            this.statements = statements;
            return this;
        }

        public Builder totalStatementCount(int totalStatementCount) {
            this.totalStatementCount = totalStatementCount;
            return this;
        }

        public Builder ignoredStatementCount(int ignoredStatementCount) {
            this.ignoredStatementCount = ignoredStatementCount;
            return this;
        }

        public Builder failedStatementCount(int failedStatementCount) {
            this.failedStatementCount = failedStatementCount;
            return this;
        }

        public Builder warnings(List<LineageWarning> warnings) {
            this.warnings = warnings;
            return this;
        }

        public LineageAnalyzeResult build() {
            return new LineageAnalyzeResult(this);
        }

        private List<LineageWarning> resolveWarnings() {
            return warnings == null ? collectWarnings(statements) : warnings;
        }
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
