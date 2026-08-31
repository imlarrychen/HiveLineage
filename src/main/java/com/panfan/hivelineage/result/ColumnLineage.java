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

    private ColumnLineage(Builder builder) {
        this.targetColumn = builder.targetColumn;
        this.sourceColumns = Collections.unmodifiableList(new ArrayList<>(builder.sourceColumns));
        this.sourceObjects = Collections.unmodifiableList(new ArrayList<>(builder.sourceObjects));
        this.expression = builder.expression;
        this.expressionExtracted = builder.expressionExtracted;
        this.lineageType = builder.lineageType;
        this.functionNames = Collections.unmodifiableList(new ArrayList<>(builder.functionNames));
        this.complexExpression = builder.complexExpression;
        this.statementIndex = builder.statementIndex;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ColumnRef targetColumn;
        private List<ColumnRef> sourceColumns = Collections.emptyList();
        private List<DataObjectRef> sourceObjects = Collections.emptyList();
        private String expression;
        private boolean expressionExtracted;
        private LineageType lineageType;
        private List<String> functionNames = Collections.emptyList();
        private boolean complexExpression;
        private int statementIndex;

        public Builder targetColumn(ColumnRef targetColumn) {
            this.targetColumn = targetColumn;
            return this;
        }

        public Builder sourceColumns(List<ColumnRef> sourceColumns) {
            this.sourceColumns = sourceColumns;
            return this;
        }

        public Builder sourceObjects(List<DataObjectRef> sourceObjects) {
            this.sourceObjects = sourceObjects;
            return this;
        }

        public Builder expression(String expression) {
            this.expression = expression;
            return this;
        }

        public Builder expressionExtracted(boolean expressionExtracted) {
            this.expressionExtracted = expressionExtracted;
            return this;
        }

        public Builder lineageType(LineageType lineageType) {
            this.lineageType = lineageType;
            return this;
        }

        public Builder functionNames(List<String> functionNames) {
            this.functionNames = functionNames;
            return this;
        }

        public Builder complexExpression(boolean complexExpression) {
            this.complexExpression = complexExpression;
            return this;
        }

        public Builder statementIndex(int statementIndex) {
            this.statementIndex = statementIndex;
            return this;
        }

        public ColumnLineage build() {
            return new ColumnLineage(this);
        }
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
