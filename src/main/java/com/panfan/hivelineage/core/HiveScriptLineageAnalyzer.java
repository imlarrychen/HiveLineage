package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.StatementParseStatus;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.result.WarningCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HiveScriptLineageAnalyzer implements HiveLineageAnalyzer {
    private static final int PARSE_STATUS_SUCCESS = StatementParseStatus.SUCCESS.getCode();
    private static final int PARSE_STATUS_FAILED = StatementParseStatus.FAILED.getCode();
    private static final int PARSE_STATUS_IGNORED = StatementParseStatus.IGNORED.getCode();
    private static final Pattern INSERT_SELECT = Pattern.compile(
        "(?is)^insert\\s+(overwrite\\s+table|into(?:\\s+table)?)\\s+([a-zA-Z_][\\w.]*)(?:\\s*\\((.*?)\\))?\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TEMPORARY_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+temporary\\s+table\\s+([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private final HiveAstParserAdapter parserAdapter;
    private final HiveScriptPreprocessor scriptPreprocessor = new HiveScriptPreprocessor();
    private final HiveTargetColumnResolver targetColumnResolver = new HiveTargetColumnResolver();
    private final HiveProjectionLineageResolver projectionResolver;
    private final HiveLineageExpansionService expansionService;
    private final HiveJoinConditionAnalyzer joinAnalyzer;

    public HiveScriptLineageAnalyzer() {
        this(new FallbackHiveAstParserAdapter(new HiveParserAstAdapter(), new HeuristicHiveAstParserAdapter()));
    }

    public HiveScriptLineageAnalyzer(HiveAstParserAdapter parserAdapter) {
        this.parserAdapter = parserAdapter == null ? new HeuristicHiveAstParserAdapter() : parserAdapter;
        this.projectionResolver = new HiveProjectionLineageResolver(this.parserAdapter, targetColumnResolver);
        this.expansionService = new HiveLineageExpansionService(this.parserAdapter, targetColumnResolver, projectionResolver);
        this.joinAnalyzer = new HiveJoinConditionAnalyzer(this.parserAdapter, targetColumnResolver, projectionResolver, expansionService);
    }

    @Override
    public LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request) {
        List<String> statements = scriptPreprocessor.splitStatements(request.getRawScript());
        List<StatementLineageResult> results = new ArrayList<>();
        Map<String, String> sessionVariables = new HashMap<>(request.getRuntimeVariables());
        Map<String, List<DataObjectRef>> temporaryTableSources = new HashMap<>();
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns = new HashMap<>();
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache = new HashMap<>();
        int ignored = 0;
        int failed = 0;

        for (int i = 0; i < statements.size(); i++) {
            String statement = statements.get(i).trim();
            if (statement.isEmpty()) {
                continue;
            }
            String executableStatement = scriptPreprocessor.stripLeadingComments(statement).trim();
            if (executableStatement.isEmpty()) {
                ignored++;
                continue;
            }
            if (scriptPreprocessor.isSetStatement(executableStatement)) {
                scriptPreprocessor.applySetStatement(executableStatement, sessionVariables);
                ignored++;
                if (request.getOptions().isIncludeIgnoredStatements()) {
                    results.add(ignoredResult(request, statement, executableStatement, i, "SET", "SESSION_SET"));
                }
                continue;
            }
            List<LineageWarning> statementWarnings = new ArrayList<>();
            String normalized = scriptPreprocessor.resolveVariables(
                executableStatement,
                sessionVariables,
                request.getOptions().getFallbackVariableValue(),
                statementWarnings,
                i
            );
            String skipReason = ignoredReason(normalized);
            if (skipReason != null) {
                if (request.getOptions().isParseJoinConditions()
                    && "PURE_QUERY".equals(skipReason)
                    && joinAnalyzer.hasJoinConditionCandidate(normalized)) {
                    results.add(joinAnalyzer.joinConditionOnlyResult(
                        request,
                        statement,
                        normalized,
                        i,
                        temporaryTableColumns,
                        viewColumnLineageCache,
                        statementWarnings
                    ));
                    continue;
                }
                ignored++;
                if (request.getOptions().isIncludeIgnoredStatements()) {
                    results.add(ignoredResult(request, statement, normalized, i, ignoredStatementType(skipReason), skipReason, statementWarnings));
                }
                continue;
            }
            StatementLineageResult result = analyzeStatement(
                request,
                statement,
                normalized,
                i,
                temporaryTableSources,
                temporaryTableColumns,
                viewColumnLineageCache,
                statementWarnings
            );
            if (Integer.valueOf(PARSE_STATUS_FAILED).equals(result.getParseStatusCode())) {
                results.add(result);
                failed++;
                continue;
            }
            if ("CREATE_TEMPORARY_TABLE_AS_SELECT".equals(result.getStatementType())) {
                expansionService.registerTemporaryTable(result, temporaryTableSources, temporaryTableColumns);
                if (request.getOptions().isIncludeTemporaryLineage()) {
                    results.add(result);
                }
                continue;
            }
            results.add(result);
        }

        return new LineageAnalyzeResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            results,
            statements.size(),
            ignored,
            failed
        );
    }

    private StatementLineageResult analyzeStatement(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        Map<String, List<DataObjectRef>> temporaryTableSources,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> statementWarnings
    ) {
        ParsedHiveStatement parsedStatement = parseStatement(normalizedStatement);
        if (isLineageStatement(parsedStatement)) {
            boolean temporaryTable = isCreateTemporaryTable(normalizedStatement);
            return analyzeSelectItemsToTarget(
                request,
                rawStatement,
                normalizedStatement,
                index,
                temporaryTable ? "CREATE_TEMPORARY_TABLE_AS_SELECT" : operationFor(parsedStatement, normalizedStatement),
                parsedStatement.getOutputObject().getQualifiedName(),
                parsedStatement.getTargetColumns(),
                parsedStatement.getSelectExpressions(),
                parsedStatement,
                temporaryTableSources,
                temporaryTableColumns,
                viewColumnLineageCache,
                statementWarnings
            );
        }

        Matcher tempMatcher = CREATE_TEMPORARY_TABLE_AS_SELECT.matcher(normalizedStatement);
        if (tempMatcher.matches()) {
            return analyzeSelectToTarget(
                request,
                rawStatement,
                normalizedStatement,
                index,
                "CREATE_TEMPORARY_TABLE_AS_SELECT",
                HiveSqlTextUtils.normalizeIdentifier(tempMatcher.group(1).trim()),
                Collections.<String>emptyList(),
                tempMatcher.group(2).trim(),
                HiveSqlTextUtils.normalizeIdentifier(tempMatcher.group(3).trim()),
                temporaryTableSources,
                temporaryTableColumns,
                viewColumnLineageCache,
                statementWarnings
            );
        }

        Matcher ctasMatcher = CREATE_TABLE_AS_SELECT.matcher(normalizedStatement);
        if (ctasMatcher.matches()) {
            return analyzeSelectToTarget(
                request,
                rawStatement,
                normalizedStatement,
                index,
                "CREATE_TABLE_AS_SELECT",
                HiveSqlTextUtils.normalizeIdentifier(ctasMatcher.group(1).trim()),
                Collections.<String>emptyList(),
                ctasMatcher.group(2).trim(),
                HiveSqlTextUtils.normalizeIdentifier(ctasMatcher.group(3).trim()),
                temporaryTableSources,
                temporaryTableColumns,
                viewColumnLineageCache,
                statementWarnings
            );
        }

        Matcher matcher = INSERT_SELECT.matcher(normalizedStatement);
        if (!matcher.matches()) {
            targetColumnResolver.addWarning(statementWarnings, WarningCode.UNSUPPORTED_STATEMENT, "Unsupported statement", index);
            return failedResult(request, rawStatement, normalizedStatement, index, "SQL_PARSE_ERROR", "Unsupported statement", statementWarnings);
        }

        String operation = matcher.group(1).toLowerCase(Locale.ROOT).startsWith("overwrite")
            ? "INSERT_OVERWRITE"
            : "INSERT_INTO";
        return analyzeSelectToTarget(
            request,
            rawStatement,
            normalizedStatement,
            index,
            operation,
            HiveSqlTextUtils.normalizeIdentifier(matcher.group(2).trim()),
            targetColumnResolver.parseTargetColumns(matcher.group(3)),
            matcher.group(4).trim(),
            HiveSqlTextUtils.normalizeIdentifier(matcher.group(5).trim()),
            temporaryTableSources,
            temporaryTableColumns,
            viewColumnLineageCache,
            statementWarnings
        );
    }

    private ParsedHiveStatement parseStatement(String normalizedStatement) {
        try {
            return parserAdapter.parse(normalizedStatement);
        } catch (RuntimeException ex) {
            return new ParsedHiveStatement(StatementType.UNSUPPORTED, null, Collections.<DataObjectRef>emptyList(), normalizedStatement);
        }
    }

    private boolean isLineageStatement(ParsedHiveStatement parsedStatement) {
        return parsedStatement != null
            && parsedStatement.getOutputObject() != null
            && !parsedStatement.getInputObjects().isEmpty()
            && !parsedStatement.getSelectExpressions().isEmpty()
            && (parsedStatement.getStatementType() == StatementType.INSERT_SELECT
            || parsedStatement.getStatementType() == StatementType.CREATE_TABLE_AS_SELECT
            || parsedStatement.getStatementType() == StatementType.CREATE_VIEW_AS_SELECT
            || parsedStatement.getStatementType() == StatementType.ALTER_VIEW_AS_SELECT);
    }

    private boolean isCreateTemporaryTable(String normalizedStatement) {
        return normalizedStatement.trim().toLowerCase(Locale.ROOT).startsWith("create temporary table ");
    }

    private String operationFor(ParsedHiveStatement parsedStatement, String normalizedStatement) {
        if (parsedStatement.getStatementType() == StatementType.CREATE_TABLE_AS_SELECT) {
            return "CREATE_TABLE_AS_SELECT";
        }
        if (parsedStatement.getStatementType() == StatementType.CREATE_VIEW_AS_SELECT) {
            return "CREATE_VIEW_AS_SELECT";
        }
        if (parsedStatement.getStatementType() == StatementType.ALTER_VIEW_AS_SELECT) {
            return "ALTER_VIEW_AS_SELECT";
        }
        String lower = normalizedStatement.trim().toLowerCase(Locale.ROOT);
        return lower.contains("insert overwrite") ? "INSERT_OVERWRITE" : "INSERT_INTO";
    }

    private StatementLineageResult analyzeSelectToTarget(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String operation,
        String targetTable,
        List<String> targetColumns,
        String selectList,
        String sourceTable,
        Map<String, List<DataObjectRef>> temporaryTableSources,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> statementWarnings
    ) {
        return analyzeSelectItemsToTarget(
            request,
            rawStatement,
            normalizedStatement,
            index,
            operation,
            targetTable,
            targetColumns,
            HiveSqlTextUtils.splitSelectItems(selectList),
            null,
            temporaryTableSources,
            temporaryTableColumns,
            viewColumnLineageCache,
            statementWarnings
        );
    }

    private StatementLineageResult analyzeSelectItemsToTarget(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String operation,
        String targetTable,
        List<String> targetColumns,
        List<String> selectItems,
        ParsedHiveStatement parsedStatement,
        Map<String, List<DataObjectRef>> temporaryTableSources,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> statementWarnings
    ) {
        LineageParseMode parseMode = request.getOptions().getParseMode();

        DataObjectRef targetObject = new DataObjectRef(targetTable);
        ParsedHiveStatement resolvedParsedStatement = parsedStatement == null ? parserAdapter.parse(normalizedStatement) : parsedStatement;
        List<DataObjectRef> originalInputObjects = resolvedParsedStatement.getInputObjects();
        if (originalInputObjects.isEmpty()) {
            return failedResult(request, rawStatement, normalizedStatement, index, "SQL_PARSE_ERROR", "No input object found", statementWarnings);
        }
        boolean starSelect = targetColumnResolver.isSingleStarSelect(selectItems);
        selectItems = targetColumnResolver.expandStarSelectItems(request, targetTable, selectItems, originalInputObjects, parseMode, statementWarnings, index);

        if (targetColumns.isEmpty()) {
            targetColumns = starSelect
                ? new ArrayList<>(selectItems)
                : targetColumnResolver.resolveTargetColumns(request, targetTable, selectItems, parseMode, statementWarnings, index);
        }
        targetColumns = alignTargetColumnsForStaticPartitions(
            targetColumns,
            selectItems.size(),
            resolvedParsedStatement.getStaticPartitionValues(),
            extractDynamicPartitionColumns(normalizedStatement)
        );
        if (targetColumns.size() != selectItems.size()) {
            return failedResult(request, rawStatement, normalizedStatement, index, "COLUMN_COUNT_MISMATCH", "Target columns and select items do not align", statementWarnings);
        }

        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables = projectionResolver.extractStatementProjectionTables(normalizedStatement);
        boolean throughTemporaryTable = expansionService.containsTemporaryTable(originalInputObjects, temporaryTableSources);
        List<DataObjectRef> columnReferenceInputObjects = expansionService.expandTemporaryInputObjects(originalInputObjects, temporaryTableSources);
        columnReferenceInputObjects = expansionService.filterProjectionInputObjects(columnReferenceInputObjects, statementProjectionTables);
        List<DataObjectRef> inputObjects = expansionService.expandViewInputObjects(request, columnReferenceInputObjects, 0, new LinkedHashSet<String>(), statementWarnings, index);
        String defaultColumnSourceTable = columnReferenceInputObjects.get(0).getQualifiedName();

        List<TableLineage> tableLineages = new ArrayList<>();
        if (parseMode == LineageParseMode.TABLE_ONLY || parseMode == LineageParseMode.TABLE_AND_COLUMN) {
            tableLineages.add(new TableLineage(
                targetObject,
                inputObjects,
                operation,
                index,
                throughTemporaryTable
            ));
        }

        List<ColumnLineage> columnLineages = new ArrayList<>();
        if (parseMode == LineageParseMode.COLUMN_ONLY || parseMode == LineageParseMode.TABLE_AND_COLUMN) {
            Map<String, String> sourceAliases = targetColumnResolver.extractSourceAliases(normalizedStatement);
            sourceAliases.putAll(resolvedParsedStatement.getSourceAliases());
            Map<String, String> topLevelSourceAliases = targetColumnResolver.extractTopLevelSourceAliases(normalizedStatement);
            targetColumnResolver.mergeTopLevelSourceAliases(sourceAliases, topLevelSourceAliases);
            Map<String, String> topLevelProjectionSourceAliases = projectionResolver.extractProjectionSourceAliases(normalizedStatement);
            sourceAliases.putAll(topLevelProjectionSourceAliases);
            Map<String, String> columnAliasSources = targetColumnResolver.filterColumnAliasSources(
                resolvedParsedStatement.getColumnAliasSources(),
                topLevelSourceAliases
            );
            Map<String, String> columnOwners = targetColumnResolver.buildColumnOwners(request, inputObjects, parseMode, statementWarnings, index);
            boolean setOperationQuery = projectionResolver.hasCompleteSetOperationProjection(resolvedParsedStatement, selectItems.size());
            for (int i = 0; i < selectItems.size(); i++) {
                String expression = HiveSqlTextUtils.stripAlias(selectItems.get(i));
                String sourceExtractionExpression = expression;
                String lineageExpression = setOperationQuery
                    ? projectionResolver.setOperationExpression(resolvedParsedStatement, normalizedStatement, i)
                    : projectionResolver.normalizeLineageExpression(expression);
                List<ColumnRef> sourceColumns = setOperationQuery
                    ? projectionResolver.setOperationSourceColumns(resolvedParsedStatement, i)
                    : Collections.<ColumnRef>emptyList();
                ColumnLineageProjection directProjection = setOperationQuery
                    ? null
                    : expansionService.resolveDirectColumnProjection(
                        request,
                        expression,
                        originalInputObjects,
                        sourceAliases,
                        topLevelProjectionSourceAliases,
                        statementProjectionTables,
                        temporaryTableColumns,
                        viewColumnLineageCache,
                        statementWarnings,
                        index
                    );
                if (directProjection != null) {
                    sourceColumns = directProjection.getSourceColumns();
                    if (directProjection.getLineageType() == LineageType.DERIVED) {
                        lineageExpression = directProjection.getExpression();
                    }
                } else if (!setOperationQuery) {
                    String aliasExpression = projectionResolver.directColumnAliasExpression(expression, resolvedParsedStatement.getColumnAliasExpressions());
                    if (aliasExpression != null) {
                        lineageExpression = aliasExpression;
                        sourceExtractionExpression = aliasExpression;
                    }
                }
                if (sourceColumns.isEmpty() && setOperationQuery) {
                    sourceColumns = projectionResolver.extractSetOperationSourceColumns(request, expression, columnReferenceInputObjects, statementWarnings, index);
                }
                if (sourceColumns.isEmpty() && directProjection == null && !projectionResolver.isConstantLineageExpression(lineageExpression)) {
                    sourceColumns = projectionResolver.extractSourceColumns(
                        sourceExtractionExpression,
                        defaultColumnSourceTable,
                        sourceAliases,
                        columnOwners,
                        columnAliasSources
                    );
                }
                if (directProjection == null) {
                    sourceColumns = expansionService.expandViewSourceColumns(request, sourceColumns, viewColumnLineageCache, 0, new LinkedHashSet<String>(), statementWarnings, index);
                }
                if (sourceColumns.isEmpty() && directProjection == null && projectionResolver.mayContainColumnReference(sourceExtractionExpression)) {
                    targetColumnResolver.addWarning(statementWarnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to resolve source columns for expression: " + sourceExtractionExpression, index);
                }
                LineageType lineageType = directProjection == null
                    ? projectionResolver.resolveLineageType(lineageExpression, sourceExtractionExpression, sourceColumns)
                    : directProjection.getLineageType();
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + targetColumns.get(i)),
                    sourceColumns,
                    inputObjects,
                    lineageExpression,
                    true,
                    lineageType,
                    lineageType == LineageType.CONSTANT ? Collections.<String>emptyList() : projectionResolver.extractFunctionNames(lineageExpression),
                    lineageType == LineageType.DERIVED,
                    index
                ));
            }
            for (Map.Entry<String, String> partitionValue : resolvedParsedStatement.getStaticPartitionValues().entrySet()) {
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + partitionValue.getKey()),
                    Collections.<ColumnRef>emptyList(),
                    inputObjects,
                    projectionResolver.normalizeLineageExpression(partitionValue.getValue()),
                    true,
                    LineageType.CONSTANT,
                    Collections.<String>emptyList(),
                    false,
                    index
                ));
            }
        }

        List<JoinCondition> joinConditions = request.getOptions().isParseJoinConditions()
            ? joinAnalyzer.buildJoinConditions(
                request,
                normalizedStatement,
                resolvedParsedStatement,
                temporaryTableColumns,
                viewColumnLineageCache,
                statementWarnings,
                index,
                rawStatement
            )
            : Collections.<JoinCondition>emptyList();

        return new StatementLineageResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            index,
            statementId(request, index),
            operation,
            rawStatement,
            normalizedStatement,
            PARSE_STATUS_SUCCESS,
            true,
            targetObject,
            inputObjects,
            parseMode,
            null,
            null,
            null,
            tableLineages,
            columnLineages,
            joinConditions,
            statementWarnings
        );
    }

    private List<String> alignTargetColumnsForStaticPartitions(
        List<String> targetColumns,
        int selectItemCount,
        Map<String, String> staticPartitionValues,
        List<String> dynamicPartitionColumns
    ) {
        if (staticPartitionValues.isEmpty() && dynamicPartitionColumns.isEmpty()) {
            return targetColumns;
        }
        List<String> nonStaticTargetColumns = new ArrayList<>();
        for (String targetColumn : targetColumns) {
            if (!staticPartitionValues.containsKey(targetColumn.toLowerCase(Locale.ROOT))) {
                nonStaticTargetColumns.add(targetColumn);
            }
        }

        if (!dynamicPartitionColumns.isEmpty()) {
            int regularColumnCount = Math.max(0, selectItemCount - dynamicPartitionColumns.size());
            if (nonStaticTargetColumns.size() >= regularColumnCount) {
                List<String> aligned = new ArrayList<>(nonStaticTargetColumns.subList(0, regularColumnCount));
                for (String dynamicPartitionColumn : dynamicPartitionColumns) {
                    if (!containsIgnoreCase(aligned, dynamicPartitionColumn)) {
                        aligned.add(preferredColumnCase(nonStaticTargetColumns, dynamicPartitionColumn));
                    }
                }
                return aligned.size() == selectItemCount ? aligned : targetColumns;
            }
        }

        return nonStaticTargetColumns.size() >= selectItemCount
            ? new ArrayList<>(nonStaticTargetColumns.subList(0, selectItemCount))
            : targetColumns;
    }

    private boolean containsIgnoreCase(List<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private String preferredColumnCase(List<String> candidates, String value) {
        for (String candidate : candidates) {
            if (candidate.equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        return value;
    }

    private List<String> extractDynamicPartitionColumns(String normalizedStatement) {
        Matcher matcher = Pattern.compile("(?is)\\bpartition\\s*\\(([^)]*)\\)").matcher(normalizedStatement);
        if (!matcher.find()) {
            return Collections.emptyList();
        }
        List<String> dynamicColumns = new ArrayList<>();
        for (String partitionItem : HiveSqlTextUtils.splitSelectItems(matcher.group(1))) {
            if (!partitionItem.contains("=")) {
                String column = partitionItem.trim();
                if (!column.isEmpty()) {
                    dynamicColumns.add(column);
                }
            }
        }
        return dynamicColumns;
    }

    private StatementLineageResult failedResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String failureCode,
        String failureMessage
    ) {
        return failedResult(
            request,
            rawStatement,
            normalizedStatement,
            index,
            failureCode,
            failureMessage,
            Collections.<LineageWarning>emptyList()
        );
    }

    private StatementLineageResult failedResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String failureCode,
        String failureMessage,
        List<LineageWarning> warnings
    ) {
        return new StatementLineageResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            index,
            statementId(request, index),
            "UNKNOWN",
            rawStatement,
            normalizedStatement,
            PARSE_STATUS_FAILED,
            false,
            null,
            Collections.emptyList(),
            request.getOptions().getParseMode(),
            null,
            failureCode,
            failureMessage,
            Collections.emptyList(),
            Collections.emptyList(),
            warnings
        );
    }

    private StatementLineageResult ignoredResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String statementType,
        String skipReason
    ) {
        return ignoredResult(
            request,
            rawStatement,
            normalizedStatement,
            index,
            statementType,
            skipReason,
            Collections.<LineageWarning>emptyList()
        );
    }

    private StatementLineageResult ignoredResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String statementType,
        String skipReason,
        List<LineageWarning> warnings
    ) {
        return new StatementLineageResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            index,
            statementId(request, index),
            statementType,
            rawStatement,
            normalizedStatement,
            PARSE_STATUS_IGNORED,
            false,
            null,
            Collections.emptyList(),
            request.getOptions().getParseMode(),
            skipReason,
            null,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            warnings
        );
    }

    private String statementId(LineageAnalyzeRequest request, int index) {
        return request.getWorkflowId() + "_" + request.getTaskId() + "_" + request.getTaskVersion() + "_" + index;
    }

    private String ignoredReason(String statement) {
        String lower = statement.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("use ")) {
            return "USE_DATABASE";
        }
        if (lower.startsWith("select ")) {
            return "PURE_QUERY";
        }
        if (lower.startsWith("with ")) {
            if (joinAnalyzer.rootSqlAfterCtes(statement).toLowerCase(Locale.ROOT).startsWith("select ")) {
                return "PURE_QUERY";
            }
        }
        if (lower.startsWith("create temporary macro ")) {
            return "CREATE_TEMPORARY_MACRO";
        }
        if (lower.startsWith("create table ") && !Pattern.compile("(?is)\\bas\\s+(?:with\\b.*)?select\\b").matcher(lower).find()) {
            return "CREATE_TABLE_DDL";
        }
        if (lower.matches("(?is)^insert\\s+overwrite\\s+(local\\s+)?directory\\b.*")) {
            return "NO_OUTPUT_TABLE";
        }
        if (lower.matches("(?is)^insert\\s+(overwrite\\s+table|into(?:\\s+table)?)\\s+.*\\s+values\\s*\\(.*")) {
            return "INSERT_VALUES";
        }
        return null;
    }

    private String ignoredStatementType(String skipReason) {
        if ("USE_DATABASE".equals(skipReason)) {
            return "USE";
        }
        if ("PURE_QUERY".equals(skipReason)) {
            return "SELECT";
        }
        if ("INSERT_VALUES".equals(skipReason)) {
            return "INSERT_VALUES";
        }
        if ("CREATE_TABLE_DDL".equals(skipReason)) {
            return "CREATE_TABLE";
        }
        if ("CREATE_TEMPORARY_MACRO".equals(skipReason)) {
            return "CREATE_TEMPORARY_MACRO";
        }
        if ("NO_OUTPUT_TABLE".equals(skipReason)) {
            return "INSERT_DIRECTORY";
        }
        return "IGNORED";
    }

}
