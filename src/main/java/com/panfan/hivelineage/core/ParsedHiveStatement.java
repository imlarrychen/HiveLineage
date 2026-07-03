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
    private final List<String> targetColumns;
    private final List<String> selectExpressions;
    private final Map<String, String> sourceAliases;
    private final Map<String, String> columnAliasSources;
    private final Map<Integer, List<String>> setOperationSourceColumnsBySelectIndex;
    private final Map<String, String> staticPartitionValues;
    private final String normalizedSql;

    public ParsedHiveStatement(
        StatementType statementType,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        String normalizedSql
    ) {
        this(
            statementType,
            outputObject,
            inputObjects,
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            normalizedSql
        );
    }

    public ParsedHiveStatement(
        StatementType statementType,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        List<String> targetColumns,
        List<String> selectExpressions,
        Map<String, String> sourceAliases,
        Map<String, String> staticPartitionValues,
        String normalizedSql
    ) {
        this(
            statementType,
            outputObject,
            inputObjects,
            targetColumns,
            selectExpressions,
            sourceAliases,
            Collections.<String, String>emptyMap(),
            Collections.<Integer, List<String>>emptyMap(),
            staticPartitionValues,
            normalizedSql
        );
    }

    public ParsedHiveStatement(
        StatementType statementType,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        List<String> targetColumns,
        List<String> selectExpressions,
        Map<String, String> sourceAliases,
        Map<String, String> columnAliasSources,
        Map<String, String> staticPartitionValues,
        String normalizedSql
    ) {
        this(
            statementType,
            outputObject,
            inputObjects,
            targetColumns,
            selectExpressions,
            sourceAliases,
            columnAliasSources,
            Collections.<Integer, List<String>>emptyMap(),
            staticPartitionValues,
            normalizedSql
        );
    }

    public ParsedHiveStatement(
        StatementType statementType,
        DataObjectRef outputObject,
        List<DataObjectRef> inputObjects,
        List<String> targetColumns,
        List<String> selectExpressions,
        Map<String, String> sourceAliases,
        Map<String, String> columnAliasSources,
        Map<Integer, List<String>> setOperationSourceColumnsBySelectIndex,
        Map<String, String> staticPartitionValues,
        String normalizedSql
    ) {
        this.statementType = statementType;
        this.outputObject = outputObject;
        this.inputObjects = Collections.unmodifiableList(new ArrayList<>(inputObjects));
        this.targetColumns = Collections.unmodifiableList(new ArrayList<>(targetColumns));
        this.selectExpressions = Collections.unmodifiableList(new ArrayList<>(selectExpressions));
        this.sourceAliases = Collections.unmodifiableMap(new HashMap<>(sourceAliases));
        this.columnAliasSources = Collections.unmodifiableMap(new HashMap<>(columnAliasSources));
        this.setOperationSourceColumnsBySelectIndex = unmodifiableNestedListMap(setOperationSourceColumnsBySelectIndex);
        this.staticPartitionValues = Collections.unmodifiableMap(new HashMap<>(staticPartitionValues));
        this.normalizedSql = normalizedSql;
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

    public Map<Integer, List<String>> getSetOperationSourceColumnsBySelectIndex() {
        return setOperationSourceColumnsBySelectIndex;
    }

    public Map<String, String> getStaticPartitionValues() {
        return staticPartitionValues;
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
