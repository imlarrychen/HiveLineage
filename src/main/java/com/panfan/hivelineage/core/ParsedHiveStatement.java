package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ParsedHiveStatement {
    private final StatementType statementType;
    private final DataObjectRef outputObject;
    private final List<DataObjectRef> inputObjects;
    private final List<DataObjectRef> projectionInputObjects;
    private final List<String> targetColumns;
    private final List<String> selectExpressions;
    private final Map<String, String> sourceAliases;
    private final Map<String, String> columnAliasSources;
    private final Map<String, String> columnAliasExpressions;
    private final Map<Integer, List<String>> setOperationSourceColumnsBySelectIndex;
    private final Map<Integer, List<String>> setOperationExpressionsBySelectIndex;
    private final Map<String, String> staticPartitionValues;
    private final List<ParsedJoinCondition> joinConditions;
    private final String normalizedSql;

    public ParsedHiveStatement(
        StatementType statementType,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        String normalizedSql
    ) {
        this.statementType = statementType;
        this.outputObject = outputObject;
        this.inputObjects = Collections.unmodifiableList(new ArrayList<>(inputObjects));
        this.projectionInputObjects = this.inputObjects;
        this.targetColumns = Collections.emptyList();
        this.selectExpressions = Collections.emptyList();
        this.sourceAliases = Collections.emptyMap();
        this.columnAliasSources = Collections.emptyMap();
        this.columnAliasExpressions = Collections.emptyMap();
        this.setOperationSourceColumnsBySelectIndex = Collections.emptyMap();
        this.setOperationExpressionsBySelectIndex = Collections.emptyMap();
        this.staticPartitionValues = Collections.emptyMap();
        this.joinConditions = Collections.emptyList();
        this.normalizedSql = normalizedSql;
    }

    private ParsedHiveStatement(Builder builder) {
        this.statementType = builder.statementType;
        this.outputObject = builder.outputObject;
        this.inputObjects = Collections.unmodifiableList(new ArrayList<>(builder.inputObjects));
        this.projectionInputObjects = Collections.unmodifiableList(new ArrayList<>(builder.projectionInputObjects));
        this.targetColumns = Collections.unmodifiableList(new ArrayList<>(builder.targetColumns));
        this.selectExpressions = Collections.unmodifiableList(new ArrayList<>(builder.selectExpressions));
        this.sourceAliases = Collections.unmodifiableMap(new HashMap<>(builder.sourceAliases));
        this.columnAliasSources = Collections.unmodifiableMap(new HashMap<>(builder.columnAliasSources));
        this.columnAliasExpressions = Collections.unmodifiableMap(new HashMap<>(builder.columnAliasExpressions));
        this.setOperationSourceColumnsBySelectIndex = unmodifiableNestedListMap(
            builder.setOperationSourceColumnsBySelectIndex
        );
        this.setOperationExpressionsBySelectIndex = unmodifiableNestedListMap(
            builder.setOperationExpressionsBySelectIndex
        );
        this.staticPartitionValues = Collections.unmodifiableMap(new HashMap<>(builder.staticPartitionValues));
        this.joinConditions = Collections.unmodifiableList(new ArrayList<>(builder.joinConditions));
        this.normalizedSql = builder.normalizedSql;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private StatementType statementType;
        private DataObjectRef outputObject;
        private List<DataObjectRef> inputObjects = Collections.emptyList();
        private List<DataObjectRef> projectionInputObjects = Collections.emptyList();
        private List<String> targetColumns = Collections.emptyList();
        private List<String> selectExpressions = Collections.emptyList();
        private Map<String, String> sourceAliases = Collections.emptyMap();
        private Map<String, String> columnAliasSources = Collections.emptyMap();
        private Map<String, String> columnAliasExpressions = Collections.emptyMap();
        private Map<Integer, List<String>> setOperationSourceColumnsBySelectIndex = Collections.emptyMap();
        private Map<Integer, List<String>> setOperationExpressionsBySelectIndex = Collections.emptyMap();
        private Map<String, String> staticPartitionValues = Collections.emptyMap();
        private List<ParsedJoinCondition> joinConditions = Collections.emptyList();
        private String normalizedSql;

        Builder statementType(StatementType statementType) {
            this.statementType = statementType;
            return this;
        }

        Builder outputObject(DataObjectRef outputObject) {
            this.outputObject = outputObject;
            return this;
        }

        Builder inputObjects(List<DataObjectRef> inputObjects) {
            this.inputObjects = inputObjects;
            return this;
        }

        Builder projectionInputObjects(List<DataObjectRef> projectionInputObjects) {
            this.projectionInputObjects = projectionInputObjects;
            return this;
        }

        Builder targetColumns(List<String> targetColumns) {
            this.targetColumns = targetColumns;
            return this;
        }

        Builder selectExpressions(List<String> selectExpressions) {
            this.selectExpressions = selectExpressions;
            return this;
        }

        Builder sourceAliases(Map<String, String> sourceAliases) {
            this.sourceAliases = sourceAliases;
            return this;
        }

        Builder columnAliasSources(Map<String, String> columnAliasSources) {
            this.columnAliasSources = columnAliasSources;
            return this;
        }

        Builder columnAliasExpressions(Map<String, String> columnAliasExpressions) {
            this.columnAliasExpressions = columnAliasExpressions;
            return this;
        }

        Builder setOperationSourceColumnsBySelectIndex(Map<Integer, List<String>> values) {
            this.setOperationSourceColumnsBySelectIndex = values;
            return this;
        }

        Builder setOperationExpressionsBySelectIndex(Map<Integer, List<String>> values) {
            this.setOperationExpressionsBySelectIndex = values;
            return this;
        }

        Builder staticPartitionValues(Map<String, String> staticPartitionValues) {
            this.staticPartitionValues = staticPartitionValues;
            return this;
        }

        Builder joinConditions(List<ParsedJoinCondition> joinConditions) {
            this.joinConditions = joinConditions;
            return this;
        }

        Builder normalizedSql(String normalizedSql) {
            this.normalizedSql = normalizedSql;
            return this;
        }

        ParsedHiveStatement build() {
            return new ParsedHiveStatement(this);
        }
    }

    public StatementType getStatementType() {
        return statementType;
    }

    public DataObjectRef getOutputObject() {
        return outputObject;
    }

    public List<DataObjectRef> getInputObjects() {
        return inputObjects;
    }

    public List<DataObjectRef> getProjectionInputObjects() {
        return projectionInputObjects.isEmpty() ? inputObjects : projectionInputObjects;
    }

    public List<String> getTargetColumns() {
        return targetColumns;
    }

    public List<String> getSelectExpressions() {
        return selectExpressions;
    }

    public Map<String, String> getSourceAliases() {
        return sourceAliases;
    }

    public Map<String, String> getColumnAliasSources() {
        return columnAliasSources;
    }

    public Map<String, String> getColumnAliasExpressions() {
        return columnAliasExpressions;
    }

    public Map<Integer, List<String>> getSetOperationSourceColumnsBySelectIndex() {
        return setOperationSourceColumnsBySelectIndex;
    }

    public Map<Integer, List<String>> getSetOperationExpressionsBySelectIndex() {
        return setOperationExpressionsBySelectIndex;
    }

    public Map<String, String> getStaticPartitionValues() {
        return staticPartitionValues;
    }

    public List<ParsedJoinCondition> getJoinConditions() {
        return joinConditions;
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }

    private static Map<Integer, List<String>> unmodifiableNestedListMap(Map<Integer, List<String>> source) {
        Map<Integer, List<String>> copied = new HashMap<>();
        for (Map.Entry<Integer, List<String>> entry : source.entrySet()) {
            copied.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copied);
    }
}
