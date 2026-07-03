package com.panfan.hivelineage.result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ColumnLineage {
    private final ColumnRef targetColumn;
    private final List<ColumnRef> sourceColumns;
    private final List<DataObjectRef> sourceObjects;
    private final String expression;
    private final boolean expressionExtracted;
    private final LineageType lineageType;
    private final List<String> functionNames;
    private final boolean complexExpression;
    private final int statementIndex;

    public ColumnLineage(
        ColumnRef targetColumn,
        List<ColumnRef> sourceColumns,
        List<DataObjectRef> sourceObjects,
        String expression,
        boolean expressionExtracted,
        LineageType lineageType,
        List<String> functionNames,
        boolean complexExpression,
        int statementIndex
    ) {
        this.targetColumn = targetColumn;
        this.sourceColumns = Collections.unmodifiableList(new ArrayList<>(sourceColumns));
        this.sourceObjects = Collections.unmodifiableList(new ArrayList<>(sourceObjects));
        this.expression = expression;
        this.expressionExtracted = expressionExtracted;
        this.lineageType = lineageType;
        this.functionNames = Collections.unmodifiableList(new ArrayList<>(functionNames));
        this.complexExpression = complexExpression;
        this.statementIndex = statementIndex;
    }

    public ColumnRef getTargetColumn() {
        return targetColumn;
    }

    public List<ColumnRef> getSourceColumns() {
        return sourceColumns;
    }

    public List<DataObjectRef> getSourceObjects() {
        return sourceObjects;
    }

    public String getExpression() {
        return expression;
    }

    public boolean isExpressionExtracted() {
        return expressionExtracted;
    }

    public LineageType getLineageType() {
        return lineageType;
    }

    public List<String> getFunctionNames() {
        return functionNames;
    }

    public boolean isComplexExpression() {
        return complexExpression;
    }

    public int getStatementIndex() {
        return statementIndex;
    }
}
