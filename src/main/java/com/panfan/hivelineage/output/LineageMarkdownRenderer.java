package com.panfan.hivelineage.output;

import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import java.util.List;

public final class LineageMarkdownRenderer {
    private LineageMarkdownRenderer() {
    }

    public static String render(LineageAnalyzeResult result, String title) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ")
            .append(isBlank(title) ? "Hive SQL Lineage Parse Result" : title)
            .append("\n\n");
        appendSummary(builder, result);
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
}
