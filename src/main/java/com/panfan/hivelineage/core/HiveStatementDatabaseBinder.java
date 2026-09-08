package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class HiveStatementDatabaseBinder {
    private static final String DOT_REGEX = "\\.";

    private final HivePhysicalNameResolver nameResolver;

    HiveStatementDatabaseBinder(HivePhysicalNameResolver nameResolver) {
        this.nameResolver = nameResolver;
    }

    ParsedHiveStatement bind(
        ParsedHiveStatement parsed,
        String statementDatabase,
        Set<String> logicalTableNames,
        Set<String> temporaryTableNames,
        boolean temporaryOutput,
        String temporaryDatabase
    ) {
        Map<String, String> physicalNames = new HashMap<>();
        Map<String, Integer> simpleNameCounts = simpleNameCounts(parsed.getInputObjects());
        List<DataObjectRef> inputs = new ArrayList<>();
        for (DataObjectRef input : parsed.getInputObjects()) {
            String raw = input.getQualifiedName();
            String temporaryName = resolveTemporaryName(temporaryTableNames, raw, temporaryDatabase);
            String bound;
            if (temporaryName != null) {
                bound = temporaryName;
            } else if (containsLogicalName(logicalTableNames, raw)) {
                bound = normalizeLogicalName(raw);
            } else {
                bound = nameResolver.qualifyTable(raw, statementDatabase);
            }
            inputs.add(new DataObjectRef(bound));
            physicalNames.put(normalizeLogicalName(raw), bound);
            String simple = simpleName(raw);
            if (Integer.valueOf(1).equals(simpleNameCounts.get(simple))) {
                physicalNames.put(simple, bound);
            }
        }
        DataObjectRef output = parsed.getOutputObject() == null
            ? null
            : new DataObjectRef(temporaryOutput
                ? HiveTemporaryTableNamer.qualify(parsed.getOutputObject().getQualifiedName(), temporaryDatabase)
                : nameResolver.qualifyTable(parsed.getOutputObject().getQualifiedName(), statementDatabase));
        Map<String, String> aliases = bindAliasTargets(parsed.getSourceAliases(), physicalNames);
        return ParsedHiveStatement.builder()
            .statementType(parsed.getStatementType())
            .outputObject(output)
            .inputObjects(inputs)
            .targetColumns(parsed.getTargetColumns())
            .selectExpressions(parsed.getSelectExpressions())
            .sourceAliases(aliases)
            .columnAliasSources(bindColumnMap(parsed.getColumnAliasSources(), aliases, physicalNames))
            .columnAliasExpressions(parsed.getColumnAliasExpressions())
            .setOperationSourceColumnsBySelectIndex(
                bindNestedColumnMap(parsed.getSetOperationSourceColumnsBySelectIndex(), aliases, physicalNames)
            )
            .setOperationExpressionsBySelectIndex(parsed.getSetOperationExpressionsBySelectIndex())
            .staticPartitionValues(parsed.getStaticPartitionValues())
            .joinConditions(bindJoinConditions(parsed.getJoinConditions(), aliases, physicalNames))
            .normalizedSql(parsed.getNormalizedSql())
            .build();
    }

    Map<String, String> bindExtractedAliasTargets(
        Map<String, String> aliases,
        List<DataObjectRef> boundInputs
    ) {
        Map<String, String> physicalNames = new HashMap<>();
        Map<String, Integer> simpleNameCounts = simpleNameCounts(boundInputs);
        for (DataObjectRef input : boundInputs) {
            String qualified = normalizeLogicalName(input.getQualifiedName());
            physicalNames.put(qualified, qualified);
            String simple = simpleName(qualified);
            if (Integer.valueOf(1).equals(simpleNameCounts.get(simple))) {
                physicalNames.put(simple, qualified);
            }
        }
        return bindAliasTargets(aliases, physicalNames);
    }

    List<ColumnRef> bindColumns(List<ColumnRef> columns, Map<String, String> aliases) {
        List<ColumnRef> bound = new ArrayList<>();
        for (ColumnRef column : columns) {
            bound.add(new ColumnRef(bindColumn(column.getQualifiedName(), aliases, aliases)));
        }
        return bound;
    }

    private Map<String, String> bindAliasTargets(Map<String, String> aliases, Map<String, String> physicalNames) {
        Map<String, String> bound = new HashMap<>();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            String target = normalizeLogicalName(entry.getValue());
            String replacement = physicalNames.get(target);
            bound.put(entry.getKey(), replacement == null ? target : replacement);
        }
        return bound;
    }

    private Map<String, String> bindColumnMap(
        Map<String, String> columns,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        Map<String, String> bound = new HashMap<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            bound.put(entry.getKey(), bindColumn(entry.getValue(), aliases, physicalNames));
        }
        return bound;
    }

    private Map<Integer, List<String>> bindNestedColumnMap(
        Map<Integer, List<String>> columnsByPosition,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        Map<Integer, List<String>> bound = new HashMap<>();
        for (Map.Entry<Integer, List<String>> entry : columnsByPosition.entrySet()) {
            List<String> columns = new ArrayList<>();
            for (String column : entry.getValue()) {
                columns.add(bindColumn(column, aliases, physicalNames));
            }
            bound.put(entry.getKey(), columns);
        }
        return bound;
    }

    private List<ParsedJoinCondition> bindJoinConditions(
        List<ParsedJoinCondition> conditions,
        Map<String, String> aliases,
        Map<String, String> physicalNames
    ) {
        List<ParsedJoinCondition> bound = new ArrayList<>();
        for (ParsedJoinCondition condition : conditions) {
            List<String> objects = new ArrayList<>();
            for (String object : condition.getDirectJoinObjects()) {
                String key = normalizeLogicalName(object);
                String replacement = aliases.get(key);
                if (replacement == null) {
                    replacement = physicalNames.get(key);
                }
                objects.add(replacement == null ? key : replacement);
            }
            List<String> columns = new ArrayList<>();
            for (String column : condition.getDirectJoinColumns()) {
                columns.add(bindColumn(column, aliases, physicalNames));
            }
            bound.add(ParsedJoinCondition.builder()
                .joinIndex(condition.getJoinIndex())
                .queryBlockPath(condition.getQueryBlockPath())
                .joinType(condition.getJoinType())
                .joinTypeName(condition.getJoinTypeName())
                .directJoinObjects(objects)
                .directJoinColumns(columns)
                .joinExpression(condition.getJoinExpression())
                .build());
        }
        return bound;
    }

    private String bindColumn(String column, Map<String, String> aliases, Map<String, String> physicalNames) {
        String normalized = normalizeLogicalName(column);
        String[] parts = normalized.split(DOT_REGEX);
        if (parts.length == 3 || parts.length == 1) {
            return normalized;
        }
        if (parts.length != 2) {
            return normalized;
        }
        String physicalOwner = aliases.get(parts[0]);
        if (physicalOwner == null) {
            physicalOwner = physicalNames.get(parts[0]);
        }
        return physicalOwner != null && physicalOwner.contains(".")
            ? physicalOwner + "." + parts[1]
            : normalized;
    }

    private Map<String, Integer> simpleNameCounts(List<DataObjectRef> objects) {
        Map<String, Integer> counts = new HashMap<>();
        for (DataObjectRef object : objects) {
            String simple = simpleName(object.getQualifiedName());
            Integer count = counts.get(simple);
            counts.put(simple, count == null ? 1 : count + 1);
        }
        return counts;
    }

    private String resolveTemporaryName(Set<String> values, String candidate, String temporaryDatabase) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String normalizedCandidate = normalizeLogicalName(candidate);
        String simpleCandidate = simpleName(normalizedCandidate);
        for (String value : values) {
            String normalizedValue = normalizeLogicalName(value);
            if (normalizedValue.equals(normalizedCandidate)
                || simpleName(normalizedValue).equals(simpleCandidate)) {
                return HiveTemporaryTableNamer.qualify(candidate, temporaryDatabase);
            }
        }
        return null;
    }

    private boolean containsLogicalName(Set<String> values, String candidate) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        String normalizedCandidate = normalizeLogicalName(candidate);
        for (String value : values) {
            if (normalizeLogicalName(value).equals(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private String simpleName(String tableName) {
        return HiveTemporaryTableNamer.simpleName(tableName);
    }

    private String normalizeLogicalName(String value) {
        return HiveTemporaryTableNamer.normalizeLogicalName(value);
    }
}
