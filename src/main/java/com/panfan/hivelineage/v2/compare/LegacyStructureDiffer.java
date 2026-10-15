package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.api.TaskIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Preserves ordering and compares public fields; new output is never auto-approved as expected.
 */
final class LegacyStructureDiffer {
    private LegacyStructureDiffer() {
    }

    static LineageDifferenceReport compare(LineageAnalyzeResult before, LineageAnalyzeResult after) {
        String caseId = after.getTaskId();
        List<Difference> differences = new ArrayList<>();
        if (!java.util.Objects.equals(before.getWorkflowId(), after.getWorkflowId())
                || !java.util.Objects.equals(before.getTaskId(), after.getTaskId())
                || !java.util.Objects.equals(before.getTaskVersion(), after.getTaskVersion())) {
            differences.add(new Difference(caseId, Optional.<StatementIdentity>empty(), DifferenceKind.IDENTITY,
                    "Result business identity differs", DifferenceDisposition.PENDING_REVIEW));
        }
        if (!warnings(before.getWarnings()).equals(warnings(after.getWarnings()))) {
            differences.add(new Difference(caseId, Optional.<StatementIdentity>empty(), DifferenceKind.DIAGNOSTIC,
                    "Result warnings: V1=" + warnings(before.getWarnings()) + "; V2=" + warnings(after.getWarnings()), DifferenceDisposition.PENDING_REVIEW));
        }
        if (before.getTotalStatementCount() != after.getTotalStatementCount()
                || before.getIgnoredStatementCount() != after.getIgnoredStatementCount()
                || before.getFailedStatementCount() != after.getFailedStatementCount()) {
            differences.add(new Difference(caseId, Optional.<StatementIdentity>empty(), DifferenceKind.STATUS,
                    "Counts: V1=" + counts(before) + "; V2=" + counts(after), DifferenceDisposition.PENDING_REVIEW));
        }
        Map<Integer, StatementLineageResult> old = new LinkedHashMap<>();
        before.getStatements().forEach(statement -> old.put(statement.getStatementIndex(), statement));
        Map<Integer, StatementLineageResult> current = new LinkedHashMap<>();
        after.getStatements().forEach(statement -> current.put(statement.getStatementIndex(), statement));
        java.util.Set<Integer> indices = new java.util.LinkedHashSet<>(old.keySet());
        indices.addAll(current.keySet());
        for (Integer index : indices) {
            StatementLineageResult left = old.get(index);
            StatementLineageResult right = current.get(index);
            StatementIdentity identity = new StatementIdentity(new TaskIdentity(after.getWorkflowId(), after.getTaskId(), after.getTaskVersion()), index,
                    after.getWorkflowId() + "_" + after.getTaskId() + "_" + after.getTaskVersion() + "_" + index);
            if (left == null || right == null) {
                differences.add(new Difference(caseId, Optional.of(identity), left == null ? DifferenceKind.ADDED : DifferenceKind.MISSING,
                        left == null ? "Statement added" : "Statement missing", DifferenceDisposition.PENDING_REVIEW));
                continue;
            }
            Map<String, String> fieldsLeft = fields(left);
            Map<String, String> fieldsRight = fields(right);
            for (String field : fieldsLeft.keySet()) {
                if (!fieldsLeft.get(field).equals(fieldsRight.get(field))) {
                    DifferenceKind kind = field.equals("identity") ? DifferenceKind.IDENTITY : field.equals("status") ? DifferenceKind.STATUS
                                                                                               : field.equals("warnings") ? DifferenceKind.DIAGNOSTIC : field.equals("expression") ? DifferenceKind.QUALITY : DifferenceKind.MAPPING;
                    differences.add(new Difference(caseId, Optional.of(identity), kind,
                            field + ": V1=" + fieldsLeft.get(field) + "; V2=" + fieldsRight.get(field), DifferenceDisposition.PENDING_REVIEW));
                }
            }
        }
        return new LineageDifferenceReport(caseId, differences, 1, 0, differences.isEmpty() ? 0 : 1);
    }

    private static String counts(LineageAnalyzeResult result) {
        return result.getTotalStatementCount() + "/" + result.getIgnoredStatementCount() + "/" + result.getFailedStatementCount();
    }

    private static Map<String, String> fields(StatementLineageResult statement) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("identity", tuple(statement.getWorkflowId(), statement.getTaskId(), statement.getTaskVersion(),
                String.valueOf(statement.getStatementIndex()), statement.getStatementId()));
        fields.put("rawSql", tuple(statement.getRawSql()));
        fields.put("normalizedSql", tuple(statement.getNormalizedSql()));
        fields.put("parseMode", String.valueOf(statement.getParseMode()));
        fields.put("status", tuple(String.valueOf(statement.getParseStatusCode()), String.valueOf(statement.isFormalLineageProduced()),
                statement.getStatementType(), statement.getSkipReason(), statement.getFailureCode()));
        fields.put("failureMessage", tuple(statement.getFailureMessage()));
        fields.put("objects", tuple(sequence(statement.getInputObjects().stream().map(object -> object.getQualifiedName()).collect(Collectors.toList())),
                statement.getOutputObject() == null ? null : statement.getOutputObject().getQualifiedName()));
        fields.put("tables", sequence(statement.getTableLineages().stream().map(table -> tuple(table.getTargetObject().getQualifiedName(),
                sequence(table.getSourceObjects().stream().map(source -> source.getQualifiedName()).collect(Collectors.toList())),
                table.getOperation(), String.valueOf(table.getStatementIndex()), String.valueOf(table.isThroughTemporaryTable()))).collect(Collectors.toList())));
        fields.put("columns", sequence(statement.getColumnLineages().stream().map(LegacyStructureDiffer::column).collect(Collectors.toList())));
        fields.put("expression", sequence(statement.getColumnLineages().stream().map(column -> tuple(column.getTargetColumn().getQualifiedName(),
                column.getExpression(), String.valueOf(column.isExpressionExtracted()), String.valueOf(column.getLineageType()), sequence(column.getFunctionNames()),
                String.valueOf(column.isComplexExpression()))).collect(Collectors.toList())));
        fields.put("joins", sequence(statement.getJoinConditions().stream().map(LegacyStructureDiffer::join).collect(Collectors.toList())));
        fields.put("warnings", warnings(statement.getWarnings()));
        return fields;
    }

    private static String column(ColumnLineage column) {
        return tuple(column.getTargetColumn().getQualifiedName(),
                sequence(column.getSourceColumns().stream().map(source -> source.getQualifiedName()).collect(Collectors.toList())),
                sequence(column.getSourceObjects().stream().map(source -> source.getQualifiedName()).collect(Collectors.toList())), String.valueOf(column.getStatementIndex()));
    }

    private static String join(JoinCondition join) {
        return tuple(String.valueOf(join.getJoinIndex()), join.getQueryBlockPath(), String.valueOf(join.getJoinType()), join.getJoinTypeName(),
                sequence(join.getDirectJoinColumns().stream().map(column -> column.getQualifiedName()).collect(Collectors.toList())),
                sequence(join.getDirectJoinObjects().stream().map(object -> object.getQualifiedName()).collect(Collectors.toList())),
                sequence(join.getJoinTables().stream().map(object -> object.getQualifiedName()).collect(Collectors.toList())),
                sequence(join.getJoinColumns().stream().map(column -> column.getQualifiedName()).collect(Collectors.toList())), join.getJoinExpression());
    }

    private static String warnings(List<LineageWarning> warnings) {
        return sequence(warnings.stream().map(warning -> tuple(warning.getWarningCode().name(), warning.getMessage(),
                String.valueOf(warning.getStatementIndex()))).collect(Collectors.toList()));
    }

    private static String sequence(List<String> values) {
        return values.size() + ":" + tuple(values.toArray(new String[0]));
    }

    private static String tuple(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                result.append("-1:");
            } else {
                result.append(value.length()).append(':').append(value);
            }
        }
        return result.toString();
    }
}
