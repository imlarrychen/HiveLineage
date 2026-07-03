package com.panfan.hivelineage;

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
    private final List<LineageWarning> warnings;

    public StatementLineageResult(
        String workflowId,
        String taskId,
        String taskVersion,
        int statementIndex,
        String statementId,
        String statementType,
        String rawSql,
        String normalizedSql,
        Integer parseStatusCode,
        boolean formalLineageProduced,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode,
        String skipReason,
        String failureCode,
        String failureMessage,
        List<TableLineage> tableLineages,
        List<ColumnLineage> columnLineages
    ) {
        this(
            workflowId,
            taskId,
            taskVersion,
            statementIndex,
            statementId,
            statementType,
            rawSql,
            normalizedSql,
            parseStatusCode,
            formalLineageProduced,
            outputObject,
            inputObjects,
            parseMode,
            skipReason,
            failureCode,
            failureMessage,
            tableLineages,
            columnLineages,
            Collections.<LineageWarning>emptyList()
        );
    }

    public StatementLineageResult(
        String workflowId,
        String taskId,
        String taskVersion,
        int statementIndex,
        String statementId,
        String statementType,
        String rawSql,
        String normalizedSql,
        Integer parseStatusCode,
        boolean formalLineageProduced,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode,
        String skipReason,
        String failureCode,
        String failureMessage,
        List<TableLineage> tableLineages,
        List<ColumnLineage> columnLineages,
        List<LineageWarning> warnings
    ) {
        this.workflowId = workflowId;
        this.taskId = taskId;
        this.taskVersion = taskVersion;
        this.statementIndex = statementIndex;
        this.statementId = statementId;
        this.statementType = statementType;
        this.rawSql = rawSql;
        this.normalizedSql = normalizedSql;
        this.parseStatusCode = parseStatusCode;
        this.formalLineageProduced = formalLineageProduced;
        this.outputObject = outputObject;
        this.inputObjects = Collections.unmodifiableList(new ArrayList<>(inputObjects));
        this.parseMode = parseMode;
        this.skipReason = skipReason;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.tableLineages = Collections.unmodifiableList(new ArrayList<>(tableLineages));
        this.columnLineages = Collections.unmodifiableList(new ArrayList<>(columnLineages));
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

    public List<LineageWarning> getWarnings() {
        return warnings;
    }
}
