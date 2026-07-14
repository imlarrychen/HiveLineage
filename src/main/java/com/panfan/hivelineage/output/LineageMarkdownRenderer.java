package com.panfan.hivelineage.output;

import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LineageMarkdownRenderer {
    private LineageMarkdownRenderer() {
    }

    public static String render(LineageAnalyzeResult result, String title) {
        return render(result, title, null);
    }

    public static String render(LineageAnalyzeResult result, String title, String defaultDatabase) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ")
            .append(isBlank(title) ? "Hive SQL Lineage Parse Result" : title)
            .append("\n\n");
        appendSummary(builder, result);
        appendTableFieldSummary(builder, result, defaultDatabase);
        for (StatementLineageResult statement : result.getStatements()) {
            appendStatement(builder, statement);
        }
        appendGlobalWarnings(builder, result);
        return builder.toString();
    }

    private static void appendSummary(StringBuilder builder, LineageAnalyzeResult result) {
        builder.append("## Parse Summary\n\n")
            .append("| Field | Value |\n")
            .append("| --- | --- |\n")
            .append(row("workflowId", result.getWorkflowId()))
            .append(row("taskId", result.getTaskId()))
            .append(row("taskVersion", result.getTaskVersion()))
            .append(row("totalStatementCount", result.getTotalStatementCount()))
            .append(row("ignoredStatementCount", result.getIgnoredStatementCount()))
            .append(row("failedStatementCount", result.getFailedStatementCount()))
            .append("\n");
    }

    private static void appendTableFieldSummary(
        StringBuilder builder,
        LineageAnalyzeResult result,
        String defaultDatabase
    ) {
        Map<String, TableFieldSummary> summaries = tableFieldSummaries(result, defaultDatabase);
        builder.append("## Tables And Fields\n\n")
            .append("> This section groups parsed table names and referenced fields for preparing Hive metadata.\n\n");
        if (summaries.isEmpty()) {
            builder.append("_None_\n\n");
            return;
        }
        builder.append("| Table | Roles | Statements | Target Fields | Source Fields | All Fields |\n")
            .append("| --- | --- | --- | --- | --- | --- |\n");
        for (TableFieldSummary summary : summaries.values()) {
            builder.append("| ")
                .append(markdown(summary.tableName))
                .append(" | ")
                .append(markdown(joinStrings(new ArrayList<>(summary.roles))))
                .append(" | ")
                .append(markdown(joinIntegers(summary.statementIndexes)))
                .append(" | ")
                .append(markdown(joinStrings(new ArrayList<>(summary.targetFields))))
                .append(" | ")
                .append(markdown(joinStrings(new ArrayList<>(summary.sourceFields))))
                .append(" | ")
                .append(markdown(joinStrings(new ArrayList<>(summary.allFields()))))
                .append(" |\n");
        }
        builder.append("\n");
    }

    private static Map<String, TableFieldSummary> tableFieldSummaries(
        LineageAnalyzeResult result,
        String defaultDatabase
    ) {
        Map<String, TableFieldSummary> summaries = new LinkedHashMap<>();
        for (StatementLineageResult statement : result.getStatements()) {
            addObjectSummary(summaries, statement.getOutputObject(), "OUTPUT", statement.getStatementIndex(), defaultDatabase);
            for (DataObjectRef inputObject : statement.getInputObjects()) {
                addObjectSummary(summaries, inputObject, "INPUT", statement.getStatementIndex(), defaultDatabase);
            }
            for (TableLineage tableLineage : statement.getTableLineages()) {
                addObjectSummary(summaries, tableLineage.getTargetObject(), "OUTPUT", statement.getStatementIndex(), defaultDatabase);
                for (DataObjectRef sourceObject : tableLineage.getSourceObjects()) {
                    addObjectSummary(summaries, sourceObject, "INPUT", statement.getStatementIndex(), defaultDatabase);
                }
            }
            for (ColumnLineage columnLineage : statement.getColumnLineages()) {
                addColumnSummary(
                    summaries,
                    columnLineage.getTargetColumn().getQualifiedName(),
                    "OUTPUT",
                    statement.getStatementIndex(),
                    defaultDatabase,
                    true
                );
                for (ColumnRef sourceColumn : columnLineage.getSourceColumns()) {
                    addColumnSummary(
                        summaries,
                        sourceColumn.getQualifiedName(),
                        "INPUT",
                        statement.getStatementIndex(),
                        defaultDatabase,
                        false
                    );
                }
                for (DataObjectRef sourceObject : columnLineage.getSourceObjects()) {
                    addObjectSummary(summaries, sourceObject, "INPUT", statement.getStatementIndex(), defaultDatabase);
                }
            }
            for (JoinCondition joinCondition : statement.getJoinConditions()) {
                for (DataObjectRef joinTable : joinCondition.getJoinTables()) {
                    addObjectSummary(summaries, joinTable, "JOIN", statement.getStatementIndex(), defaultDatabase);
                }
                for (ColumnRef joinColumn : joinCondition.getJoinColumns()) {
                    addColumnSummary(
                        summaries,
                        joinColumn.getQualifiedName(),
                        "JOIN",
                        statement.getStatementIndex(),
                        defaultDatabase,
                        false
                    );
                }
            }
        }
        return summaries;
    }

    private static void addObjectSummary(
        Map<String, TableFieldSummary> summaries,
        DataObjectRef objectRef,
        String role,
        int statementIndex,
        String defaultDatabase
    ) {
        String tableName = objectName(objectRef);
        if (isBlank(tableName)) {
            return;
        }
        summaryFor(summaries, resolveTableName(tableName, defaultDatabase))
            .addRole(role, statementIndex);
    }

    private static void addColumnSummary(
        Map<String, TableFieldSummary> summaries,
        String qualifiedColumnName,
        String role,
        int statementIndex,
        String defaultDatabase,
        boolean targetField
    ) {
        ColumnNameParts parts = splitColumnName(qualifiedColumnName);
        if (parts == null) {
            return;
        }
        TableFieldSummary summary = summaryFor(summaries, resolveTableName(parts.tableName, defaultDatabase));
        summary.addRole(role, statementIndex);
        if (targetField) {
            summary.targetFields.add(parts.columnName);
        } else {
            summary.sourceFields.add(parts.columnName);
        }
    }

    private static TableFieldSummary summaryFor(Map<String, TableFieldSummary> summaries, String tableName) {
        TableFieldSummary summary = summaries.get(tableName);
        if (summary == null) {
            summary = new TableFieldSummary(tableName);
            summaries.put(tableName, summary);
        }
        return summary;
    }

    private static String resolveTableName(String tableName, String defaultDatabase) {
        if (isBlank(tableName) || tableName.contains(".") || isBlank(defaultDatabase)) {
            return tableName;
        }
        return defaultDatabase + "." + tableName;
    }

    private static ColumnNameParts splitColumnName(String qualifiedColumnName) {
        if (isBlank(qualifiedColumnName)) {
            return null;
        }
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == qualifiedColumnName.length() - 1) {
            return null;
        }
        return new ColumnNameParts(
            qualifiedColumnName.substring(0, dotIndex),
            qualifiedColumnName.substring(dotIndex + 1)
        );
    }

    private static void appendStatement(StringBuilder builder, StatementLineageResult statement) {
        builder.append("## Statement ")
            .append(statement.getStatementIndex())
            .append("\n\n")
            .append("```sql\n")
            .append(nullToEmpty(statement.getRawSql()).trim())
            .append("\n```\n\n")
            .append("| Field | Value |\n")
            .append("| --- | --- |\n")
            .append(row("statementId", statement.getStatementId()))
            .append(row("statementType", statement.getStatementType()))
            .append(row("parseStatusCode", statement.getParseStatusCode()))
            .append(row("formalLineageProduced", statement.isFormalLineageProduced()))
            .append(row("parseMode", statement.getParseMode()))
            .append(row("outputObject", objectName(statement.getOutputObject())))
            .append(row("inputObjects", joinObjects(statement.getInputObjects())))
            .append(row("skipReason", statement.getSkipReason()))
            .append(row("failureCode", statement.getFailureCode()))
            .append(row("failureMessage", statement.getFailureMessage()))
            .append("\n");
        appendTableLineage(builder, statement);
        appendColumnLineage(builder, statement);
        appendJoinConditions(builder, statement);
        appendStatementWarnings(builder, statement);
    }

    private static void appendTableLineage(StringBuilder builder, StatementLineageResult statement) {
        builder.append("### Table Lineage\n\n");
        if (statement.getTableLineages().isEmpty()) {
            builder.append("_None_\n\n");
            return;
        }
        builder.append("| Target | Sources | Operation | Through Temporary Table |\n")
            .append("| --- | --- | --- | --- |\n");
        for (TableLineage tableLineage : statement.getTableLineages()) {
            builder.append("| ")
                .append(markdown(objectName(tableLineage.getTargetObject())))
                .append(" | ")
                .append(markdown(joinObjects(tableLineage.getSourceObjects())))
                .append(" | ")
                .append(markdown(tableLineage.getOperation()))
                .append(" | ")
                .append(tableLineage.isThroughTemporaryTable())
                .append(" |\n");
        }
        builder.append("\n");
    }

    private static void appendColumnLineage(StringBuilder builder, StatementLineageResult statement) {
        builder.append("### Column Lineage\n\n");
        if (statement.getColumnLineages().isEmpty()) {
            builder.append("_None_\n\n");
            return;
        }
        builder.append("| Target Column | Source Columns | Source Objects | Type | Expression | Functions | Complex | Extracted |\n")
            .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ColumnLineage columnLineage : statement.getColumnLineages()) {
            builder.append("| ")
                .append(markdown(columnLineage.getTargetColumn().getQualifiedName()))
                .append(" | ")
                .append(markdown(joinColumns(columnLineage.getSourceColumns())))
                .append(" | ")
                .append(markdown(joinObjects(columnLineage.getSourceObjects())))
                .append(" | ")
                .append(markdown(String.valueOf(columnLineage.getLineageType())))
                .append(" | ")
                .append(markdown(columnLineage.getExpression()))
                .append(" | ")
                .append(markdown(joinStrings(columnLineage.getFunctionNames())))
                .append(" | ")
                .append(columnLineage.isComplexExpression())
                .append(" | ")
                .append(columnLineage.isExpressionExtracted())
                .append(" |\n");
        }
        builder.append("\n");
    }

    private static void appendJoinConditions(StringBuilder builder, StatementLineageResult statement) {
        builder.append("### Join Conditions\n\n");
        if (statement.getJoinConditions().isEmpty()) {
            builder.append("_None_\n\n");
            return;
        }
        builder.append("| Index | Query Block | Type | Direct Objects | Direct Columns | Join Tables | Join Columns | Expression |\n")
            .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (JoinCondition joinCondition : statement.getJoinConditions()) {
            builder.append("| ")
                .append(joinCondition.getJoinIndex())
                .append(" | ")
                .append(markdown(joinCondition.getQueryBlockPath()))
                .append(" | ")
                .append(markdown(joinCondition.getJoinTypeName()))
                .append(" | ")
                .append(markdown(joinObjects(joinCondition.getDirectJoinObjects())))
                .append(" | ")
                .append(markdown(joinColumns(joinCondition.getDirectJoinColumns())))
                .append(" | ")
                .append(markdown(joinObjects(joinCondition.getJoinTables())))
                .append(" | ")
                .append(markdown(joinColumns(joinCondition.getJoinColumns())))
                .append(" | ")
                .append(markdown(joinCondition.getJoinExpression()))
                .append(" |\n");
        }
        builder.append("\n");
    }

    private static void appendStatementWarnings(StringBuilder builder, StatementLineageResult statement) {
        builder.append("### Warnings\n\n");
        if (statement.getWarnings().isEmpty()) {
            builder.append("_None_\n\n");
            return;
        }
        builder.append("| Code | Message |\n")
            .append("| --- | --- |\n");
        for (LineageWarning warning : statement.getWarnings()) {
            builder.append("| ")
                .append(markdown(String.valueOf(warning.getWarningCode())))
                .append(" | ")
                .append(markdown(warning.getMessage()))
                .append(" |\n");
        }
        builder.append("\n");
    }

    private static void appendGlobalWarnings(StringBuilder builder, LineageAnalyzeResult result) {
        builder.append("## All Warnings\n\n");
        if (result.getWarnings().isEmpty()) {
            builder.append("_None_\n");
            return;
        }
        builder.append("| Statement | Code | Message |\n")
            .append("| --- | --- | --- |\n");
        for (LineageWarning warning : result.getWarnings()) {
            builder.append("| ")
                .append(warning.getStatementIndex())
                .append(" | ")
                .append(markdown(String.valueOf(warning.getWarningCode())))
                .append(" | ")
                .append(markdown(warning.getMessage()))
                .append(" |\n");
        }
    }

    private static String row(String field, Object value) {
        return "| " + markdown(field) + " | " + markdown(value == null ? "" : String.valueOf(value)) + " |\n";
    }

    private static String objectName(DataObjectRef objectRef) {
        return objectRef == null ? "" : objectRef.getQualifiedName();
    }

    private static String joinObjects(List<DataObjectRef> objects) {
        StringBuilder builder = new StringBuilder();
        for (DataObjectRef object : objects) {
            appendJoined(builder, object.getQualifiedName());
        }
        return builder.toString();
    }

    private static String joinColumns(List<ColumnRef> columns) {
        StringBuilder builder = new StringBuilder();
        for (ColumnRef column : columns) {
            appendJoined(builder, column.getQualifiedName());
        }
        return builder.toString();
    }

    private static String joinStrings(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            appendJoined(builder, value);
        }
        return builder.toString();
    }

    private static String joinIntegers(Set<Integer> values) {
        StringBuilder builder = new StringBuilder();
        for (Integer value : values) {
            appendJoined(builder, String.valueOf(value));
        }
        return builder.toString();
    }

    private static void appendJoined(StringBuilder builder, String value) {
        if (builder.length() > 0) {
            builder.append("<br>");
        }
        builder.append(value);
    }

    private static String markdown(String value) {
        return nullToEmpty(value)
            .replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class TableFieldSummary {
        private final String tableName;
        private final Set<String> roles = new LinkedHashSet<>();
        private final Set<Integer> statementIndexes = new LinkedHashSet<>();
        private final Set<String> targetFields = new LinkedHashSet<>();
        private final Set<String> sourceFields = new LinkedHashSet<>();

        private TableFieldSummary(String tableName) {
            this.tableName = tableName;
        }

        private void addRole(String role, int statementIndex) {
            roles.add(role);
            statementIndexes.add(statementIndex);
        }

        private List<String> allFields() {
            List<String> fields = new ArrayList<>();
            fields.addAll(targetFields);
            for (String sourceField : sourceFields) {
                if (!fields.contains(sourceField)) {
                    fields.add(sourceField);
                }
            }
            return fields;
        }
    }

    private static final class ColumnNameParts {
        private final String tableName;
        private final String columnName;

        private ColumnNameParts(String tableName, String columnName) {
            this.tableName = tableName;
            this.columnName = columnName;
        }
    }
}
