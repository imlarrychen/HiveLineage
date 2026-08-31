package com.panfan.hivelineage.result;

import com.panfan.hivelineage.request.LineageParseMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StatementLineageResult {
    private final String workflowId;
    private final String taskId;
    private final String taskVersion;
    private final int statementIndex;
    private final String statementId;
    private final String statementType;
    private final String rawSql;
    private final String normalizedSql;
    private final Integer parseStatusCode;
    private final boolean formalLineageProduced;
    private final DataObjectRef outputObject;
    private final List<DataObjectRef> inputObjects;
    private final LineageParseMode parseMode;
    private final String skipReason;
    private final String failureCode;
    private final String failureMessage;
    private final List<TableLineage> tableLineages;
    private final List<ColumnLineage> columnLineages;
    private final List<JoinCondition> joinConditions;
    private final List<LineageWarning> warnings;

    private StatementLineageResult(Builder builder) {
        this.workflowId = builder.workflowId;
        this.taskId = builder.taskId;
        this.taskVersion = builder.taskVersion;
        this.statementIndex = builder.statementIndex;
        this.statementId = builder.statementId;
        this.statementType = builder.statementType;
        this.rawSql = builder.rawSql;
        this.normalizedSql = builder.normalizedSql;
        this.parseStatusCode = builder.parseStatusCode;
        this.formalLineageProduced = builder.formalLineageProduced;
        this.outputObject = builder.outputObject;
        this.inputObjects = Collections.unmodifiableList(new ArrayList<>(builder.inputObjects));
        this.parseMode = builder.parseMode;
        this.skipReason = builder.skipReason;
        this.failureCode = builder.failureCode;
        this.failureMessage = builder.failureMessage;
        this.tableLineages = Collections.unmodifiableList(new ArrayList<>(builder.tableLineages));
        this.columnLineages = Collections.unmodifiableList(new ArrayList<>(builder.columnLineages));
        this.joinConditions = Collections.unmodifiableList(new ArrayList<>(builder.joinConditions));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(builder.warnings));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String workflowId;
        private String taskId;
        private String taskVersion;
        private int statementIndex;
        private String statementId;
        private String statementType;
        private String rawSql;
        private String normalizedSql;
        private Integer parseStatusCode;
        private boolean formalLineageProduced;
        private DataObjectRef outputObject;
        private List<DataObjectRef> inputObjects = Collections.emptyList();
        private LineageParseMode parseMode;
        private String skipReason;
        private String failureCode;
        private String failureMessage;
        private List<TableLineage> tableLineages = Collections.emptyList();
        private List<ColumnLineage> columnLineages = Collections.emptyList();
        private List<JoinCondition> joinConditions = Collections.emptyList();
        private List<LineageWarning> warnings = Collections.emptyList();

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

        public Builder statementIndex(int statementIndex) {
            this.statementIndex = statementIndex;
            return this;
        }

        public Builder statementId(String statementId) {
            this.statementId = statementId;
            return this;
        }

        public Builder statementType(String statementType) {
            this.statementType = statementType;
            return this;
        }

        public Builder rawSql(String rawSql) {
            this.rawSql = rawSql;
            return this;
        }

        public Builder normalizedSql(String normalizedSql) {
            this.normalizedSql = normalizedSql;
            return this;
        }

        public Builder parseStatusCode(Integer parseStatusCode) {
            this.parseStatusCode = parseStatusCode;
            return this;
        }

        public Builder formalLineageProduced(boolean formalLineageProduced) {
            this.formalLineageProduced = formalLineageProduced;
            return this;
        }

        public Builder outputObject(DataObjectRef outputObject) {
            this.outputObject = outputObject;
            return this;
        }

        public Builder inputObjects(List<DataObjectRef> inputObjects) {
            this.inputObjects = inputObjects;
            return this;
        }

        public Builder parseMode(LineageParseMode parseMode) {
            this.parseMode = parseMode;
            return this;
        }

        public Builder skipReason(String skipReason) {
            this.skipReason = skipReason;
            return this;
        }

        public Builder failureCode(String failureCode) {
            this.failureCode = failureCode;
            return this;
        }

        public Builder failureMessage(String failureMessage) {
            this.failureMessage = failureMessage;
            return this;
        }

        public Builder tableLineages(List<TableLineage> tableLineages) {
            this.tableLineages = tableLineages;
            return this;
        }

        public Builder columnLineages(List<ColumnLineage> columnLineages) {
            this.columnLineages = columnLineages;
            return this;
        }

        public Builder joinConditions(List<JoinCondition> joinConditions) {
            this.joinConditions = joinConditions;
            return this;
        }

        public Builder warnings(List<LineageWarning> warnings) {
            this.warnings = warnings;
            return this;
        }

        public StatementLineageResult build() {
            return new StatementLineageResult(this);
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

    public int getStatementIndex() {
        return statementIndex;
    }

    public String getStatementId() {
        return statementId;
    }

    public String getStatementType() {
        return statementType;
    }

    public String getRawSql() {
        return rawSql;
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }

    public Integer getParseStatusCode() {
        return parseStatusCode;
    }

    public boolean isFormalLineageProduced() {
        return formalLineageProduced;
    }

    public DataObjectRef getOutputObject() {
        return outputObject;
    }

    public List<DataObjectRef> getInputObjects() {
        return inputObjects;
    }

    public LineageParseMode getParseMode() {
        return parseMode;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public List<TableLineage> getTableLineages() {
        return tableLineages;
    }

    public List<ColumnLineage> getColumnLineages() {
        return columnLineages;
    }

    public List<JoinCondition> getJoinConditions() {
        return joinConditions;
    }

    public List<LineageWarning> getWarnings() {
        return warnings;
    }
}
