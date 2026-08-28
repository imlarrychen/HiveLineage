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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HiveScriptLineageAnalyzer implements HiveLineageAnalyzer {
    private static final int PARSE_STATUS_SUCCESS = StatementParseStatus.SUCCESS.getCode();
    private static final int PARSE_STATUS_FAILED = StatementParseStatus.FAILED.getCode();
    private static final int PARSE_STATUS_IGNORED = StatementParseStatus.IGNORED.getCode();
    private static final Pattern INSERT_SELECT = Pattern.compile(
        "(?is)^insert\\s+(overwrite\\s+table|into(?:\\s+table)?)\\s+([a-zA-Z_][\\w.]*)"
            + "(?:\\s*\\((.*?)\\))?\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TEMPORARY_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+temporary\\s+table\\s+([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private final HiveAstParserAdapter parserAdapter;
    private final HivePhysicalNameResolver nameResolver;
    private final HiveStatementDatabaseBinder databaseBinder;
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
        this.nameResolver = new HivePhysicalNameResolver();
        this.databaseBinder = new HiveStatementDatabaseBinder(nameResolver);
        this.projectionResolver = new HiveProjectionLineageResolver(this.parserAdapter, targetColumnResolver);
        this.expansionService = new HiveLineageExpansionService(this.parserAdapter, targetColumnResolver, projectionResolver);
        this.joinAnalyzer = new HiveJoinConditionAnalyzer(this.parserAdapter, targetColumnResolver, projectionResolver, expansionService);
    }

    @Override
    public LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request) {
        List<String> statements = scriptPreprocessor.splitStatements(request.getRawScript());
        List<StatementLineageResult> results = new ArrayList<>();
        Map<String, String> sessionVariables = new HashMap<>(request.getRuntimeVariables());
        ScriptAnalysisState scriptState = new ScriptAnalysisState(request.getDefaultDatabase(), nameResolver);
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
            StatementAnalysisContext context = new StatementAnalysisContext(
                request,
                statement,
                normalized,
                i,
                scriptState,
                scriptState.getCurrentDatabase(),
                statementWarnings
            );
            if (skipReason != null) {
                if (request.getOptions().isParseJoinConditions()
                    && "PURE_QUERY".equals(skipReason)
                    && joinAnalyzer.hasJoinConditionCandidate(normalized)) {
                    results.add(joinAnalyzer.joinConditionOnlyResult(context));
                    continue;
                }
                ignored++;
                if (request.getOptions().isIncludeIgnoredStatements()) {
                    results.add(ignoredResult(context, ignoredStatementType(skipReason), skipReason));
                }
                continue;
            }
            StatementLineageResult result = analyzeStatement(context);
            if (Integer.valueOf(PARSE_STATUS_FAILED).equals(result.getParseStatusCode())) {
                results.add(result);
                failed++;
                continue;
            }
            if ("CREATE_TEMPORARY_TABLE_AS_SELECT".equals(result.getStatementType())) {
                expansionService.registerTemporaryTable(
                    result,
                    scriptState.getTemporaryTableSources(),
                    scriptState.getTemporaryTableColumns()
                );
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

    private StatementLineageResult analyzeStatement(StatementAnalysisContext context) {
        String normalizedStatement = context.getNormalizedStatement();
        ParsedHiveStatement parsedStatement = parseStatement(context, normalizedStatement);
        if (isLineageStatement(parsedStatement)) {
            boolean temporaryTable = isCreateTemporaryTable(normalizedStatement);
            return analyzeSelectItemsToTarget(
                context,
                temporaryTable ? "CREATE_TEMPORARY_TABLE_AS_SELECT" : operationFor(parsedStatement, normalizedStatement),
                parsedStatement.getOutputObject().getQualifiedName(),
                parsedStatement.getTargetColumns(),
                parsedStatement.getSelectExpressions(),
                parsedStatement
            );
        }

        Matcher tempMatcher = CREATE_TEMPORARY_TABLE_AS_SELECT.matcher(normalizedStatement);
        if (tempMatcher.matches()) {
            return analyzeSelectToTarget(
                context,
                "CREATE_TEMPORARY_TABLE_AS_SELECT",
                HiveSqlTextUtils.normalizeIdentifier(tempMatcher.group(1).trim()),
                Collections.<String>emptyList(),
                tempMatcher.group(2).trim()
            );
        }

        Matcher ctasMatcher = CREATE_TABLE_AS_SELECT.matcher(normalizedStatement);
        if (ctasMatcher.matches()) {
            return analyzeSelectToTarget(
                context,
                "CREATE_TABLE_AS_SELECT",
                HiveSqlTextUtils.normalizeIdentifier(ctasMatcher.group(1).trim()),
                Collections.<String>emptyList(),
                ctasMatcher.group(2).trim()
            );
        }

        Matcher matcher = INSERT_SELECT.matcher(normalizedStatement);
        if (!matcher.matches()) {
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNSUPPORTED_STATEMENT,
                "Unsupported statement",
                context.getStatementIndex()
            );
            return failedResult(context, "SQL_PARSE_ERROR", "Unsupported statement");
        }

        String operation = matcher.group(1).toLowerCase(Locale.ROOT).startsWith("overwrite")
            ? "INSERT_OVERWRITE"
            : "INSERT_INTO";
        return analyzeSelectToTarget(
            context,
            operation,
            HiveSqlTextUtils.normalizeIdentifier(matcher.group(2).trim()),
            targetColumnResolver.parseTargetColumns(matcher.group(3)),
            matcher.group(4).trim()
        );
    }

    private ParsedHiveStatement parseStatement(StatementAnalysisContext context, String normalizedStatement) {
        ParsedHiveStatement parsed;
        try {
            parsed = parserAdapter.parse(normalizedStatement);
        } catch (RuntimeException ex) {
            return new ParsedHiveStatement(StatementType.UNSUPPORTED, null, Collections.<DataObjectRef>emptyList(), normalizedStatement);
        }
        return bindParsedStatement(context, normalizedStatement, parsed);
    }

    private ParsedHiveStatement parseAndBind(StatementAnalysisContext context, String sql) {
        return bindParsedStatement(context, sql, parserAdapter.parse(sql));
    }

    private ParsedHiveStatement bindParsedStatement(
        StatementAnalysisContext context,
        String sql,
        ParsedHiveStatement parsed
    ) {
        return databaseBinder.bind(
            parsed,
            context.getStatementDatabase(),
            context.getTemporaryTableSources().keySet(),
            isCreateTemporaryTable(sql)
        );
    }

    private boolean isLineageStatement(ParsedHiveStatement parsedStatement) {
        return hasLineageShape(parsedStatement)
            && isSupportedLineageType(parsedStatement.getStatementType());
    }

    private boolean hasLineageShape(ParsedHiveStatement parsedStatement) {
        return parsedStatement != null
            && parsedStatement.getOutputObject() != null
            && !parsedStatement.getInputObjects().isEmpty()
            && !parsedStatement.getSelectExpressions().isEmpty();
    }

    private boolean isSupportedLineageType(StatementType statementType) {
        return statementType == StatementType.INSERT_SELECT
            || statementType == StatementType.CREATE_TABLE_AS_SELECT
            || statementType == StatementType.CREATE_VIEW_AS_SELECT
            || statementType == StatementType.ALTER_VIEW_AS_SELECT;
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
        StatementAnalysisContext context,
        String operation,
        String targetTable,
        List<String> targetColumns,
        String selectList
    ) {
        return analyzeSelectItemsToTarget(
            context,
            operation,
            targetTable,
            targetColumns,
            HiveSqlTextUtils.splitSelectItems(selectList),
            null
        );
    }

    private StatementLineageResult analyzeSelectItemsToTarget(
        StatementAnalysisContext context,
        String operation,
        String targetTable,
        List<String> targetColumns,
        List<String> selectItems,
        ParsedHiveStatement parsedStatement
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        String rawStatement = context.getRawStatement();
        String normalizedStatement = context.getNormalizedStatement();
        int index = context.getStatementIndex();
        List<LineageWarning> statementWarnings = context.getWarnings();
        LineageParseMode parseMode = request.getOptions().getParseMode();

        ParsedHiveStatement resolvedParsedStatement = parsedStatement == null
            ? parseAndBind(context, normalizedStatement)
            : parsedStatement;
        if (resolvedParsedStatement.getOutputObject() != null) {
            targetTable = resolvedParsedStatement.getOutputObject().getQualifiedName();
        }
        DataObjectRef targetObject = new DataObjectRef(targetTable);
        List<DataObjectRef> originalInputObjects = resolvedParsedStatement.getInputObjects();
        if (originalInputObjects.isEmpty()) {
            return failedResult(context, "SQL_PARSE_ERROR", "No input object found");
        }
        ColumnResolutionContext columnContext = new ColumnResolutionContext(request, parseMode, statementWarnings, index);
        HiveSetOperationStarExpander setOperationStarExpander = new HiveSetOperationStarExpander(targetColumnResolver);
        SetOperationExpansionResult setExpansion = setOperationStarExpander.expand(
            columnContext,
            normalizedStatement
        );
        boolean topLevelSetOperation = setExpansion.isSetOperation();
        if (topLevelSetOperation && !setExpansion.hasConsistentBranchWidths()) {
            return failedResult(context, "COLUMN_COUNT_MISMATCH", "Set operation branch columns do not align");
        }
        String analysisStatement = setExpansion.getSql();
        if (!analysisStatement.equals(normalizedStatement)) {
            resolvedParsedStatement = parseAndBind(context, analysisStatement);
            originalInputObjects = resolvedParsedStatement.getInputObjects();
            selectItems = resolvedParsedStatement.getSelectExpressions();
        }
        Map<String, String> sourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractSourceAliases(analysisStatement),
            originalInputObjects
        );
        sourceAliases.putAll(resolvedParsedStatement.getSourceAliases());
        Map<String, String> topLevelSourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractTopLevelSourceAliases(analysisStatement),
            originalInputObjects
        );
        targetColumnResolver.mergeTopLevelSourceAliases(sourceAliases, topLevelSourceAliases);
        Map<String, String> topLevelProjectionSourceAliases = databaseBinder.bindExtractedAliasTargets(
            projectionResolver.extractProjectionSourceAliases(analysisStatement),
            originalInputObjects
        );
        sourceAliases.putAll(topLevelProjectionSourceAliases);
        Map<String, String> explicitSourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractExplicitSourceAliases(analysisStatement),
            originalInputObjects
        );
        QualifiedOwnerResolver ownerResolver = new QualifiedOwnerResolver(originalInputObjects, explicitSourceAliases);
        boolean starSelect = targetColumnResolver.isSingleStarSelect(selectItems);
        boolean positionalSingleDirectSourceStar = isPositionalSingleDirectSourceStar(
            operation,
            targetColumns,
            starSelect,
            topLevelSetOperation,
            analysisStatement,
            originalInputObjects,
            topLevelSourceAliases
        ) && targetColumnResolver.hasColumnMetadataWithoutWarning(request, originalInputObjects.get(0));
        List<String> expandedSelectItems = topLevelSetOperation
            ? selectItems
            : positionalSingleDirectSourceStar
            ? targetColumnResolver.expandPositionalSingleInputStar(
                columnContext,
                selectItems,
                originalInputObjects.get(0)
            )
            : targetColumnResolver.expandStarSelectItems(
                columnContext,
                targetTable,
                selectItems,
                originalInputObjects,
                ownerResolver
            );

        List<String> inferredTargetColumns = targetColumns;
        if (targetColumns.isEmpty()) {
            inferredTargetColumns = positionalSingleDirectSourceStar
                ? targetColumnResolver.resolveStarTargetColumns(
                    request,
                    targetTable,
                    expandedSelectItems,
                    parseMode,
                    statementWarnings,
                    index
                )
                : starSelect
                ? new ArrayList<>(expandedSelectItems)
                : targetColumnResolver.resolveTargetColumns(
                    request,
                    targetTable,
                    expandedSelectItems,
                    parseMode,
                    statementWarnings,
                    index
                );
        }
        List<String> resolvedTargetColumns = alignTargetColumnsForStaticPartitions(
            inferredTargetColumns,
            expandedSelectItems.size(),
            resolvedParsedStatement.getStaticPartitionValues(),
            extractDynamicPartitionColumns(analysisStatement)
        );
        if (resolvedTargetColumns.size() != expandedSelectItems.size()) {
            return failedResult(context, "COLUMN_COUNT_MISMATCH", "Target columns and select items do not align");
        }

        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables =
            projectionResolver.bindPhysicalProjectionSources(
                projectionResolver.extractStatementProjectionTables(analysisStatement),
                sourceAliases,
                databaseBinder
            );
        boolean throughTemporaryTable = expansionService.containsTemporaryTable(
            originalInputObjects,
            context.getTemporaryTableSources()
        );
        List<DataObjectRef> expandedTemporaryInputs = expansionService.expandTemporaryInputObjects(
            originalInputObjects,
            context.getTemporaryTableSources()
        );
        List<DataObjectRef> columnReferenceInputObjects = expansionService.filterProjectionInputObjects(
            expandedTemporaryInputs,
            statementProjectionTables
        );
        List<DataObjectRef> inputObjects = expansionService.expandViewInputObjects(
            context,
            columnReferenceInputObjects,
            0,
            new LinkedHashSet<String>()
        );
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
            Map<String, String> columnAliasSources = targetColumnResolver.filterColumnAliasSources(
                resolvedParsedStatement.getColumnAliasSources(),
                topLevelSourceAliases
            );
            Map<String, String> columnOwners = targetColumnResolver.buildColumnOwners(request, inputObjects, parseMode, statementWarnings, index);
            boolean setOperationQuery = projectionResolver.hasCompleteSetOperationProjection(
                resolvedParsedStatement,
                expandedSelectItems.size()
            );
            for (int i = 0; i < expandedSelectItems.size(); i++) {
                boolean unresolvedSetPosition = setExpansion.isUnresolvedPosition(i);
                String expression = HiveSqlTextUtils.stripAlias(expandedSelectItems.get(i));
                QualifiedColumnReference qualifiedReference = QualifiedColumnReference.parse(expression);
                boolean unresolvedStarExpression = qualifiedReference != null && qualifiedReference.isStar();
                String sourceExtractionExpression = expression;
                String lineageExpression = setOperationQuery
                    ? projectionResolver.setOperationExpression(resolvedParsedStatement, analysisStatement, i)
                    : projectionResolver.normalizeLineageExpression(expression);
                List<ColumnRef> sourceColumns = setOperationQuery
                    ? projectionResolver.setOperationSourceColumns(resolvedParsedStatement, i)
                    : Collections.<ColumnRef>emptyList();
                if (setOperationQuery && unresolvedSetPosition) {
                    sourceColumns = Collections.emptyList();
                }
                ColumnLineageProjection directProjection = setOperationQuery
                    ? null
                    : expansionService.resolveDirectColumnProjection(
                        context,
                        expression,
                        originalInputObjects,
                        sourceAliases,
                        topLevelProjectionSourceAliases,
                        statementProjectionTables,
                        ownerResolver
                    );
                if (directProjection != null) {
                    sourceColumns = directProjection.getSourceColumns();
                    if (directProjection.getLineageType() == LineageType.DERIVED) {
                        lineageExpression = directProjection.getExpression();
                    }
                } else if (!setOperationQuery) {
                    String aliasExpression = projectionResolver.directColumnAliasExpression(
                        expression,
                        resolvedParsedStatement.getColumnAliasExpressions()
                    );
                    if (aliasExpression != null) {
                        lineageExpression = aliasExpression;
                        sourceExtractionExpression = aliasExpression;
                    }
                }
                if (sourceColumns.isEmpty() && setOperationQuery && !unresolvedSetPosition) {
                    sourceColumns = projectionResolver.extractSetOperationSourceColumns(
                        request,
                        expression,
                        columnReferenceInputObjects,
                        statementWarnings,
                        index
                    );
                }
                if (sourceColumns.isEmpty()
                    && directProjection == null
                    && !unresolvedSetPosition
                    && !unresolvedStarExpression
                    && !projectionResolver.isConstantLineageExpression(lineageExpression)) {
                    sourceColumns = projectionResolver.extractSourceColumns(
                        sourceExtractionExpression,
                        defaultColumnSourceTable,
                        sourceAliases,
                        columnOwners,
                        columnAliasSources,
                        ownerResolver
                    );
                }
                if (directProjection == null) {
                    sourceColumns = expansionService.expandViewSourceColumns(
                        context,
                        sourceColumns,
                        0,
                        new LinkedHashSet<String>()
                    );
                }
                boolean unresolvedDirectProjection = directProjection != null && directProjection.isUnresolved();
                if (unresolvedDirectProjection
                    || (sourceColumns.isEmpty()
                    && directProjection == null
                    && !unresolvedStarExpression
                    && projectionResolver.mayContainColumnReference(sourceExtractionExpression))) {
                    targetColumnResolver.addWarning(
                        statementWarnings,
                        WarningCode.UNKNOWN_COLUMN_LINEAGE,
                        "Unable to resolve source columns for expression: " + sourceExtractionExpression,
                        index
                    );
                }
                LineageType lineageType = directProjection == null
                    ? projectionResolver.resolveLineageType(lineageExpression, sourceExtractionExpression, sourceColumns)
                    : directProjection.getLineageType();
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + resolvedTargetColumns.get(i)),
                    sourceColumns,
                    inputObjects,
                    lineageExpression,
                    true,
                    lineageType,
                    lineageType == LineageType.CONSTANT
                        ? Collections.<String>emptyList()
                        : projectionResolver.extractFunctionNames(lineageExpression),
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
            ? joinAnalyzer.buildJoinConditions(context, resolvedParsedStatement)
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

    private StatementLineageResult failedResult(
        StatementAnalysisContext context,
        String failureCode,
        String failureMessage
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        int index = context.getStatementIndex();
        return new StatementLineageResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            index,
            statementId(request, index),
            "UNKNOWN",
            context.getRawStatement(),
            context.getNormalizedStatement(),
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
            context.getWarnings()
        );
    }

    private boolean isPositionalSingleDirectSourceStar(
        String operation,
        List<String> targetColumns,
        boolean starSelect,
        boolean topLevelSetOperation,
        String analysisStatement,
        List<DataObjectRef> inputObjects,
        Map<String, String> topLevelSourceAliases
    ) {
        if (!operation.startsWith("INSERT_")
            || !targetColumns.isEmpty()
            || !starSelect
            || topLevelSetOperation
            || !targetColumnResolver.hasSingleTopLevelDirectSourceRelation(analysisStatement)
            || inputObjects.size() != 1) {
            return false;
        }
        Set<String> topLevelSources = new LinkedHashSet<>(topLevelSourceAliases.values());
        return topLevelSources.size() == 1
            && topLevelSources.iterator().next().equalsIgnoreCase(inputObjects.get(0).getQualifiedName());
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

    private StatementLineageResult ignoredResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String statementType,
        String skipReason
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
            Collections.<LineageWarning>emptyList()
        );
    }

    private StatementLineageResult ignoredResult(
        StatementAnalysisContext context,
        String statementType,
        String skipReason
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        int index = context.getStatementIndex();
        return new StatementLineageResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            index,
            statementId(request, index),
            statementType,
            context.getRawStatement(),
            context.getNormalizedStatement(),
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
            context.getWarnings()
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
