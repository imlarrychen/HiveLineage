package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
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
import java.util.Arrays;
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
        "(?is)^insert\\s+(overwrite\\s+table|into(?:\\s+table)?)\\s+([a-zA-Z_][\\w.]*)(?:\\s*\\((.*?)\\))?\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TEMPORARY_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+temporary\\s+table\\s+([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern SOURCE_OBJECT = Pattern.compile("(?is)\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)");
    private static final Pattern SOURCE_ALIAS = Pattern.compile("(?is)\\b(?:from|join)\\s+([a-zA-Z_][\\w.]*)\\s+(?:as\\s+)?([a-zA-Z_][\\w]*)\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-zA-Z_][\\w]*)(?:\\.([a-zA-Z_][\\w]*))?\\b");
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile("\\b([a-zA-Z_][\\w]*)\\.([a-zA-Z_][\\w]*)\\b");
    private final HiveAstParserAdapter parserAdapter;

    public HiveScriptLineageAnalyzer() {
        this(new FallbackHiveAstParserAdapter(new HiveParserAstAdapter(), new HeuristicHiveAstParserAdapter()));
    }

    public HiveScriptLineageAnalyzer(HiveAstParserAdapter parserAdapter) {
        this.parserAdapter = parserAdapter == null ? new HeuristicHiveAstParserAdapter() : parserAdapter;
    }

    @Override
    public LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request) {
        List<String> statements = splitStatements(request.getRawScript());
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
            String executableStatement = stripLeadingComments(statement).trim();
            if (executableStatement.isEmpty()) {
                ignored++;
                continue;
            }
            if (isSetStatement(executableStatement)) {
                applySetStatement(executableStatement, sessionVariables);
                ignored++;
                if (request.getOptions().isIncludeIgnoredStatements()) {
                    results.add(ignoredResult(request, statement, executableStatement, i, "SET", "SESSION_SET"));
                }
                continue;
            }
            List<LineageWarning> statementWarnings = new ArrayList<>();
            String normalized = resolveVariables(
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
                    && hasJoinConditionCandidate(normalized)) {
                    results.add(joinConditionOnlyResult(
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
                registerTemporaryTable(result, temporaryTableSources, temporaryTableColumns);
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
                normalizeIdentifier(tempMatcher.group(1).trim()),
                Collections.<String>emptyList(),
                tempMatcher.group(2).trim(),
                normalizeIdentifier(tempMatcher.group(3).trim()),
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
                normalizeIdentifier(ctasMatcher.group(1).trim()),
                Collections.<String>emptyList(),
                ctasMatcher.group(2).trim(),
                normalizeIdentifier(ctasMatcher.group(3).trim()),
                temporaryTableSources,
                temporaryTableColumns,
                viewColumnLineageCache,
                statementWarnings
            );
        }

        Matcher matcher = INSERT_SELECT.matcher(normalizedStatement);
        if (!matcher.matches()) {
            addWarning(statementWarnings, WarningCode.UNSUPPORTED_STATEMENT, "Unsupported statement", index);
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
            normalizeIdentifier(matcher.group(2).trim()),
            parseTargetColumns(matcher.group(3)),
            matcher.group(4).trim(),
            normalizeIdentifier(matcher.group(5).trim()),
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
            splitSelectItems(selectList),
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
        boolean starSelect = isSingleStarSelect(selectItems);
        selectItems = expandStarSelectItems(request, targetTable, selectItems, originalInputObjects, parseMode, statementWarnings, index);

        if (targetColumns.isEmpty()) {
            targetColumns = starSelect
                ? new ArrayList<>(selectItems)
                : resolveTargetColumns(request, targetTable, selectItems, parseMode, statementWarnings, index);
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

        boolean throughTemporaryTable = containsTemporaryTable(originalInputObjects, temporaryTableSources);
        List<DataObjectRef> columnReferenceInputObjects = expandTemporaryInputObjects(originalInputObjects, temporaryTableSources);
        List<DataObjectRef> inputObjects = expandViewInputObjects(request, columnReferenceInputObjects, 0, new LinkedHashSet<String>(), statementWarnings, index);
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
            Map<String, String> sourceAliases = extractSourceAliases(normalizedStatement);
            sourceAliases.putAll(resolvedParsedStatement.getSourceAliases());
            Map<String, String> topLevelSourceAliases = extractTopLevelSourceAliases(normalizedStatement);
            mergeTopLevelSourceAliases(sourceAliases, topLevelSourceAliases);
            Map<String, String> columnAliasSources = filterColumnAliasSources(
                resolvedParsedStatement.getColumnAliasSources(),
                topLevelSourceAliases
            );
            Map<String, String> columnOwners = buildColumnOwners(request, inputObjects, parseMode, statementWarnings, index);
            boolean setOperationQuery = hasCompleteSetOperationProjection(resolvedParsedStatement, selectItems.size());
            for (int i = 0; i < selectItems.size(); i++) {
                String expression = stripAlias(selectItems.get(i));
                String sourceExtractionExpression = expression;
                String lineageExpression = setOperationQuery
                    ? setOperationExpression(resolvedParsedStatement, normalizedStatement, i)
                    : normalizeLineageExpression(expression);
                List<ColumnRef> sourceColumns = setOperationQuery
                    ? setOperationSourceColumns(resolvedParsedStatement, i)
                    : Collections.<ColumnRef>emptyList();
                ColumnLineageProjection directProjection = setOperationQuery
                    ? null
                    : resolveDirectColumnProjection(
                        request,
                        expression,
                        originalInputObjects,
                        sourceAliases,
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
                    String aliasExpression = directColumnAliasExpression(expression, resolvedParsedStatement.getColumnAliasExpressions());
                    if (aliasExpression != null) {
                        lineageExpression = aliasExpression;
                        sourceExtractionExpression = aliasExpression;
                    }
                }
                if (sourceColumns.isEmpty() && setOperationQuery) {
                    sourceColumns = extractSetOperationSourceColumns(request, expression, columnReferenceInputObjects, statementWarnings, index);
                }
                if (sourceColumns.isEmpty() && directProjection == null && !isConstantLineageExpression(lineageExpression)) {
                    sourceColumns = extractSourceColumns(
                        sourceExtractionExpression,
                        defaultColumnSourceTable,
                        sourceAliases,
                        columnOwners,
                        columnAliasSources
                    );
                }
                if (directProjection == null) {
                    sourceColumns = expandViewSourceColumns(request, sourceColumns, viewColumnLineageCache, 0, new LinkedHashSet<String>(), statementWarnings, index);
                }
                if (sourceColumns.isEmpty() && mayContainColumnReference(sourceExtractionExpression)) {
                    addWarning(statementWarnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to resolve source columns for expression: " + sourceExtractionExpression, index);
                }
                LineageType lineageType = directProjection == null
                    ? resolveLineageType(lineageExpression, sourceExtractionExpression, sourceColumns)
                    : directProjection.getLineageType();
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + targetColumns.get(i)),
                    sourceColumns,
                    inputObjects,
                    lineageExpression,
                    true,
                    lineageType,
                    lineageType == LineageType.CONSTANT ? Collections.<String>emptyList() : extractFunctionNames(lineageExpression),
                    lineageType == LineageType.DERIVED,
                    index
                ));
            }
            for (Map.Entry<String, String> partitionValue : resolvedParsedStatement.getStaticPartitionValues().entrySet()) {
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + partitionValue.getKey()),
                    Collections.<ColumnRef>emptyList(),
                    inputObjects,
                    normalizeLineageExpression(partitionValue.getValue()),
                    true,
                    LineageType.CONSTANT,
                    Collections.<String>emptyList(),
                    false,
                    index
                ));
            }
        }

        List<JoinCondition> joinConditions = request.getOptions().isParseJoinConditions()
            ? buildJoinConditions(
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

    private List<JoinCondition> buildJoinConditions(
        LineageAnalyzeRequest request,
        String normalizedStatement,
        ParsedHiveStatement parsedStatement,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        int statementIndex,
        String rawStatement
    ) {
        if (parsedStatement == null || parsedStatement.getJoinConditions().isEmpty()) {
            return extractJoinConditions(
                request,
                rawStatement,
                normalizedStatement,
                statementIndex,
                temporaryTableColumns,
                viewColumnLineageCache,
                warnings
            );
        }
        CteExtraction cteExtraction = extractCtes(normalizedStatement);
        Map<String, Map<String, List<ColumnRef>>> cteColumnProjections = new HashMap<>();
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            cteColumnProjections.put(
                cteBlock.getName().toLowerCase(Locale.ROOT),
                extractCteColumnProjections(cteBlock.getSql(), cteColumnProjections)
            );
        }

        List<JoinCondition> conditions = new ArrayList<>();
        for (ParsedJoinCondition parsedJoinCondition : parsedStatement.getJoinConditions()) {
            List<ColumnRef> directColumns = toColumnRefs(parsedJoinCondition.getDirectJoinColumns());
            List<DataObjectRef> directObjects = toDataObjectRefs(parsedJoinCondition.getDirectJoinObjects());
            Map<String, String> sourceAliases = sourceAliasesFromDirectJoin(
                parsedJoinCondition.getDirectJoinColumns(),
                parsedJoinCondition.getDirectJoinObjects()
            );
            List<ColumnRef> joinColumns = resolveJoinColumns(
                request,
                directColumns,
                sourceAliases,
                parsedStatement.getInputObjects(),
                cteColumnProjections,
                temporaryTableColumns,
                viewColumnLineageCache,
                warnings,
                statementIndex
            );
            if (directObjects.isEmpty() && hasUnqualifiedColumns(directColumns)) {
                directObjects = joinTables(joinColumns, directObjects);
            }
            conditions.add(new JoinCondition(
                parsedJoinCondition.getJoinIndex(),
                parsedJoinCondition.getQueryBlockPath(),
                parsedJoinCondition.getJoinType(),
                parsedJoinCondition.getJoinTypeName(),
                directObjects,
                directColumns,
                joinTables(joinColumns, directObjects),
                joinColumns,
                parsedJoinCondition.getJoinExpression()
            ));
        }
        return conditions;
    }

    private List<ColumnRef> toColumnRefs(List<String> qualifiedNames) {
        List<ColumnRef> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String qualifiedName : qualifiedNames) {
            addColumn(columns, seen, new ColumnRef(qualifiedName));
        }
        return columns;
    }

    private List<DataObjectRef> toDataObjectRefs(List<String> qualifiedNames) {
        List<DataObjectRef> objects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String qualifiedName : qualifiedNames) {
            addInputObject(objects, seen, new DataObjectRef(qualifiedName));
        }
        return objects;
    }

    private Map<String, String> sourceAliasesFromDirectJoin(List<String> directColumns, List<String> directObjects) {
        Map<String, String> aliases = new HashMap<>();
        int objectIndex = 0;
        Set<String> seenOwners = new LinkedHashSet<>();
        for (String directColumn : directColumns) {
            String owner = tableNameOfColumn(directColumn);
            if (owner == null || !seenOwners.add(owner.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String object = objectIndex < directObjects.size() ? directObjects.get(objectIndex) : owner;
            objectIndex++;
            aliases.put(owner.toLowerCase(Locale.ROOT), object);
            aliases.put(object.toLowerCase(Locale.ROOT), object);
            String simpleName = object.contains(".") ? object.substring(object.lastIndexOf('.') + 1) : object;
            aliases.put(simpleName.toLowerCase(Locale.ROOT), object);
        }
        return aliases;
    }

    private StatementLineageResult joinConditionOnlyResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> statementWarnings
    ) {
        ParsedHiveStatement parsedStatement = parseStatement(normalizedStatement);
        List<JoinCondition> joinConditions = buildJoinConditions(
            request,
            normalizedStatement,
            parsedStatement,
            temporaryTableColumns,
            viewColumnLineageCache,
            statementWarnings,
            index,
            rawStatement
        );
        return new StatementLineageResult(
            request.getWorkflowId(),
            request.getTaskId(),
            request.getTaskVersion(),
            index,
            statementId(request, index),
            "SELECT",
            rawStatement,
            normalizedStatement,
            PARSE_STATUS_SUCCESS,
            false,
            null,
            joinInputObjects(joinConditions),
            request.getOptions().getParseMode(),
            null,
            null,
            null,
            Collections.<TableLineage>emptyList(),
            Collections.<ColumnLineage>emptyList(),
            joinConditions,
            statementWarnings
        );
    }

    private boolean hasJoinConditionCandidate(String statement) {
        return Pattern.compile("(?is)\\bjoin\\b.+\\bon\\b").matcher(statement).find()
            || Pattern.compile("(?is)\\bfrom\\b[^;]+,[^;]+\\bwhere\\b").matcher(statement).find();
    }

    private List<DataObjectRef> joinInputObjects(List<JoinCondition> joinConditions) {
        List<DataObjectRef> inputObjects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JoinCondition joinCondition : joinConditions) {
            for (DataObjectRef joinTable : joinCondition.getJoinTables()) {
                addInputObject(inputObjects, seen, joinTable);
            }
        }
        if (!inputObjects.isEmpty()) {
            return inputObjects;
        }
        for (JoinCondition joinCondition : joinConditions) {
            for (DataObjectRef directObject : joinCondition.getDirectJoinObjects()) {
                addInputObject(inputObjects, seen, directObject);
            }
        }
        return inputObjects;
    }

    private List<JoinCondition> extractJoinConditions(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int statementIndex,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings
    ) {
        if (!hasJoinConditionCandidate(normalizedStatement)) {
            return Collections.emptyList();
        }
        CteExtraction cteExtraction = extractCtes(normalizedStatement);
        Map<String, Map<String, List<ColumnRef>>> cteColumnProjections = new HashMap<>();
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            cteColumnProjections.put(
                cteBlock.getName().toLowerCase(Locale.ROOT),
                extractCteColumnProjections(cteBlock.getSql(), cteColumnProjections)
            );
        }

        List<JoinCondition> conditions = new ArrayList<>();
        JoinIndexCounter counter = new JoinIndexCounter();
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            extractJoinConditionsFromQueryBlock(
                request,
                cteBlock.getSql(),
                "cte." + cteBlock.getName(),
                statementIndex,
                counter,
                cteColumnProjections,
                temporaryTableColumns,
                viewColumnLineageCache,
                warnings,
                conditions
            );
        }
        extractJoinConditionsFromQueryBlock(
            request,
            cteExtraction.getRootSql(),
            "root",
            statementIndex,
            counter,
            cteColumnProjections,
            temporaryTableColumns,
            viewColumnLineageCache,
            warnings,
            conditions
        );
        return conditions;
    }

    private void extractJoinConditionsFromQueryBlock(
        LineageAnalyzeRequest request,
        String queryBlockSql,
        String queryBlockPath,
        int statementIndex,
        JoinIndexCounter counter,
        Map<String, Map<String, List<ColumnRef>>> cteColumnProjections,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        List<JoinCondition> conditions
    ) {
        Map<String, String> sourceAliases = extractTopLevelSourceAliases(queryBlockSql);
        List<JoinClause> clauses = collectTopLevelJoinClauses(queryBlockSql);
        for (JoinClause clause : clauses) {
            List<ColumnRef> directColumns = extractDirectJoinColumns(clause.getExpression());
            List<DataObjectRef> directObjects = directJoinObjects(directColumns, sourceAliases);
            List<ColumnRef> joinColumns = resolveJoinColumns(
                request,
                directColumns,
                sourceAliases,
                inputObjectsFromSourceAliases(sourceAliases),
                cteColumnProjections,
                temporaryTableColumns,
                viewColumnLineageCache,
                warnings,
                statementIndex
            );
            List<DataObjectRef> joinTables = joinTables(joinColumns, directObjects);
            JoinTypeInfo joinTypeInfo = resolveJoinType(queryBlockSql, clause.getJoinKeywordIndex());
            conditions.add(new JoinCondition(
                counter.next(),
                queryBlockPath,
                joinTypeInfo.getCode(),
                joinTypeInfo.getName(),
                directObjects,
                directColumns,
                joinTables,
                joinColumns,
                clause.getExpression()
            ));
        }
    }

    private List<JoinClause> collectTopLevelJoinClauses(String sql) {
        List<JoinClause> clauses = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && matchesKeywordAt(sql, i, "join")) {
                int onIndex = topLevelKeywordIndex(sql, "on", i + 4);
                if (onIndex < 0) {
                    continue;
                }
                int expressionStart = skipWhitespace(sql, onIndex + 2);
                int expressionEnd = findJoinExpressionEnd(sql, expressionStart);
                String expression = sql.substring(expressionStart, expressionEnd).trim();
                if (!expression.isEmpty()) {
                    clauses.add(new JoinClause(i, expression));
                }
                i = Math.max(i, expressionEnd - 1);
            }
        }
        return clauses;
    }

    private int findJoinExpressionEnd(String sql, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = start; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && (
                matchesKeywordAt(sql, i, "join")
                    || matchesKeywordAt(sql, i, "where")
                    || matchesKeywordAt(sql, i, "group")
                    || matchesKeywordAt(sql, i, "having")
                    || matchesKeywordAt(sql, i, "order")
                    || matchesKeywordAt(sql, i, "limit")
                    || matchesKeywordAt(sql, i, "union"))) {
                return i;
            }
        }
        return sql.length();
    }

    private int topLevelKeywordIndex(String sql, String keyword, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = Math.max(0, start); i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && matchesKeywordAt(sql, i, keyword)) {
                return i;
            }
        }
        return -1;
    }

    private List<ColumnRef> extractDirectJoinColumns(String expression) {
        List<ColumnRef> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = QUALIFIED_COLUMN.matcher(removeStringLiterals(expression));
        while (matcher.find()) {
            String owner = matcher.group(1);
            String column = matcher.group(2);
            String qualifiedName = owner + "." + column;
            if (seen.add(qualifiedName.toLowerCase(Locale.ROOT))) {
                columns.add(new ColumnRef(qualifiedName));
            }
        }
        return columns;
    }

    private List<DataObjectRef> directJoinObjects(List<ColumnRef> directColumns, Map<String, String> sourceAliases) {
        List<DataObjectRef> objects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef directColumn : directColumns) {
            String tableName = tableNameOfColumn(directColumn.getQualifiedName());
            if (tableName == null) {
                continue;
            }
            String resolved = sourceAliases.get(tableName.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                resolved = tableName;
            }
            addInputObject(objects, seen, new DataObjectRef(resolved));
        }
        return objects;
    }

    private List<ColumnRef> resolveJoinColumns(
        LineageAnalyzeRequest request,
        List<ColumnRef> directColumns,
        Map<String, String> sourceAliases,
        List<DataObjectRef> inputObjects,
        Map<String, Map<String, List<ColumnRef>>> cteColumnProjections,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        List<ColumnRef> resolvedColumns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef directColumn : directColumns) {
            String owner = tableNameOfColumn(directColumn.getQualifiedName());
            String columnName = owner == null ? directColumn.getQualifiedName() : columnNameOf(directColumn.getQualifiedName());
            if (columnName == null) {
                continue;
            }
            List<String> sourceObjects = owner == null
                ? resolveUnqualifiedJoinColumnOwners(request, columnName, inputObjects, warnings, statementIndex)
                : Collections.singletonList(resolveSourceObject(owner, sourceAliases));
            for (String sourceObject : sourceObjects) {
                List<ColumnRef> expanded = expandJoinColumn(
                    request,
                    sourceObject,
                    columnName,
                    cteColumnProjections,
                    temporaryTableColumns,
                    viewColumnLineageCache,
                    warnings,
                    statementIndex
                );
                for (ColumnRef expandedColumn : expanded) {
                    addColumn(resolvedColumns, seen, expandedColumn);
                }
            }
        }
        return resolvedColumns;
    }

    private String resolveSourceObject(String owner, Map<String, String> sourceAliases) {
        String sourceObject = sourceAliases.get(owner.toLowerCase(Locale.ROOT));
        return sourceObject == null ? owner : sourceObject;
    }

    private List<String> resolveUnqualifiedJoinColumnOwners(
        LineageAnalyzeRequest request,
        String columnName,
        List<DataObjectRef> inputObjects,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (inputObjects.isEmpty()) {
            addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to resolve unqualified join column " + columnName, statementIndex);
            return Collections.emptyList();
        }
        List<String> owners = new ArrayList<>();
        String normalizedColumn = columnName.toLowerCase(Locale.ROOT);
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata != null && containsColumn(metadata, normalizedColumn)) {
                owners.add(inputObject.getQualifiedName());
            }
        }
        if (owners.size() == 1) {
            return owners;
        }
        String message = owners.isEmpty()
            ? "Unable to resolve unqualified join column " + columnName
            : "Ambiguous unqualified join column " + columnName + " in " + joinWithComma(owners);
        addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, message, statementIndex);
        return Collections.emptyList();
    }

    private boolean hasUnqualifiedColumns(List<ColumnRef> columns) {
        for (ColumnRef column : columns) {
            if (tableNameOfColumn(column.getQualifiedName()) == null) {
                return true;
            }
        }
        return false;
    }

    private List<DataObjectRef> inputObjectsFromSourceAliases(Map<String, String> sourceAliases) {
        List<DataObjectRef> inputObjects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String sourceObject : sourceAliases.values()) {
            if (sourceObject.contains(".")) {
                addInputObject(inputObjects, seen, new DataObjectRef(sourceObject));
            }
        }
        return inputObjects;
    }

    private List<ColumnRef> expandJoinColumn(
        LineageAnalyzeRequest request,
        String sourceObject,
        String columnName,
        Map<String, Map<String, List<ColumnRef>>> cteColumnProjections,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        ColumnLineageProjection temporaryProjection = columnProjection(sourceObject, columnName, temporaryTableColumns);
        if (temporaryProjection != null && !temporaryProjection.getSourceColumns().isEmpty()) {
            return temporaryProjection.getSourceColumns();
        }
        if (containsProjectionTable(sourceObject, temporaryTableColumns)) {
            addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to map temporary table join column " + sourceObject + "." + columnName, statementIndex);
            return Collections.emptyList();
        }

        Map<String, List<ColumnRef>> cteProjection = cteColumnProjections.get(sourceObject.toLowerCase(Locale.ROOT));
        if (cteProjection != null) {
            List<ColumnRef> projectedColumns = cteProjection.get(columnName.toLowerCase(Locale.ROOT));
            if (projectedColumns != null && !projectedColumns.isEmpty()) {
                return projectedColumns;
            }
            addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to map CTE join column " + sourceObject + "." + columnName, statementIndex);
            return Collections.emptyList();
        }

        ColumnLineageProjection viewProjection = viewColumnProjection(
            request,
            sourceObject,
            columnName,
            viewColumnLineageCache,
            warnings,
            statementIndex
        );
        if (viewProjection != null && !viewProjection.getSourceColumns().isEmpty()) {
            return viewProjection.getSourceColumns();
        }
        return Collections.singletonList(new ColumnRef(sourceObject + "." + columnName));
    }

    private boolean containsProjectionTable(
        String tableName,
        Map<String, Map<String, ColumnLineageProjection>> projectionsByTable
    ) {
        if (projectionsByTable.containsKey(tableName)) {
            return true;
        }
        return tableName.contains(".")
            && projectionsByTable.containsKey(tableName.substring(tableName.lastIndexOf('.') + 1));
    }

    private List<DataObjectRef> joinTables(List<ColumnRef> joinColumns, List<DataObjectRef> directObjects) {
        List<DataObjectRef> tables = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef joinColumn : joinColumns) {
            String tableName = tableNameOfColumn(joinColumn.getQualifiedName());
            if (tableName != null) {
                addInputObject(tables, seen, new DataObjectRef(tableName));
            }
        }
        if (!tables.isEmpty()) {
            return tables;
        }
        for (DataObjectRef directObject : directObjects) {
            addInputObject(tables, seen, directObject);
        }
        return tables;
    }

    private Map<String, List<ColumnRef>> extractCteColumnProjections(
        String cteSql,
        Map<String, Map<String, List<ColumnRef>>> existingCteColumnProjections
    ) {
        int selectIndex = topLevelKeywordIndex(cteSql, "select", 0);
        int fromIndex = topLevelKeywordIndex(cteSql, "from", selectIndex < 0 ? 0 : selectIndex);
        if (selectIndex < 0 || fromIndex < 0 || fromIndex <= selectIndex) {
            return Collections.emptyMap();
        }
        String selectList = cteSql.substring(selectIndex + 6, fromIndex);
        Map<String, String> sourceAliases = extractTopLevelSourceAliases(cteSql);
        String defaultSourceTable = firstAliasTarget(sourceAliases);
        Map<String, List<ColumnRef>> projections = new HashMap<>();
        for (String selectItem : splitSelectItems(selectList)) {
            String outputColumn = extractAlias(selectItem);
            String expression = stripAlias(selectItem);
            if (outputColumn == null && isDirectExpression(expression)) {
                outputColumn = columnNameOf(expression);
                if (outputColumn == null) {
                    outputColumn = expression;
                }
            }
            if (outputColumn == null) {
                continue;
            }
            List<ColumnRef> sourceColumns = extractSourceColumns(
                expression,
                defaultSourceTable,
                sourceAliases,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap()
            );
            List<ColumnRef> expanded = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (ColumnRef sourceColumn : sourceColumns) {
                String sourceTable = tableNameOfColumn(sourceColumn.getQualifiedName());
                String sourceColumnName = columnNameOf(sourceColumn.getQualifiedName());
                List<ColumnRef> nested = sourceTable == null || sourceColumnName == null
                    ? Collections.singletonList(sourceColumn)
                    : expandCteProjectionColumn(sourceTable, sourceColumnName, existingCteColumnProjections);
                for (ColumnRef nestedColumn : nested) {
                    addColumn(expanded, seen, nestedColumn);
                }
            }
            projections.put(outputColumn.toLowerCase(Locale.ROOT), expanded);
        }
        return projections;
    }

    private List<ColumnRef> expandCteProjectionColumn(
        String sourceTable,
        String sourceColumnName,
        Map<String, Map<String, List<ColumnRef>>> existingCteColumnProjections
    ) {
        Map<String, List<ColumnRef>> projection = existingCteColumnProjections.get(sourceTable.toLowerCase(Locale.ROOT));
        if (projection == null) {
            return Collections.singletonList(new ColumnRef(sourceTable + "." + sourceColumnName));
        }
        List<ColumnRef> projectedColumns = projection.get(sourceColumnName.toLowerCase(Locale.ROOT));
        return projectedColumns == null || projectedColumns.isEmpty()
            ? Collections.singletonList(new ColumnRef(sourceTable + "." + sourceColumnName))
            : projectedColumns;
    }

    private String firstAliasTarget(Map<String, String> sourceAliases) {
        for (Map.Entry<String, String> alias : sourceAliases.entrySet()) {
            if (alias.getKey().equals(alias.getValue()) || alias.getValue().contains(".")) {
                return alias.getValue();
            }
        }
        return "";
    }

    private CteExtraction extractCtes(String sql) {
        String trimmed = sql.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("with ")) {
            return new CteExtraction(Collections.<CteBlock>emptyList(), trimmed);
        }
        List<CteBlock> cteBlocks = new ArrayList<>();
        int position = skipWhitespace(trimmed, 4);
        while (position < trimmed.length()) {
            int nameStart = position;
            int nameEnd = readIdentifier(trimmed, nameStart);
            if (nameEnd <= nameStart) {
                break;
            }
            String cteName = normalizeIdentifier(trimmed.substring(nameStart, nameEnd));
            position = skipWhitespace(trimmed, nameEnd);
            if (position < trimmed.length() && trimmed.charAt(position) == '(') {
                position = findMatchingParen(trimmed, position) + 1;
                position = skipWhitespace(trimmed, position);
            }
            int asStart = position;
            int asEnd = readIdentifier(trimmed, asStart);
            if (asEnd <= asStart || !"as".equalsIgnoreCase(trimmed.substring(asStart, asEnd))) {
                break;
            }
            position = skipWhitespace(trimmed, asEnd);
            if (position >= trimmed.length() || trimmed.charAt(position) != '(') {
                break;
            }
            int bodyStart = position + 1;
            int bodyEnd = findMatchingParen(trimmed, position);
            if (bodyEnd < bodyStart) {
                break;
            }
            cteBlocks.add(new CteBlock(cteName, trimmed.substring(bodyStart, bodyEnd).trim()));
            position = skipWhitespace(trimmed, bodyEnd + 1);
            if (position < trimmed.length() && trimmed.charAt(position) == ',') {
                position = skipWhitespace(trimmed, position + 1);
                continue;
            }
            break;
        }
        String rootSql = position < trimmed.length() ? trimmed.substring(position).trim() : "";
        return new CteExtraction(cteBlocks, rootSql);
    }

    private int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private JoinTypeInfo resolveJoinType(String sql, int joinKeywordIndex) {
        String prefix = sql.substring(Math.max(0, joinKeywordIndex - 32), joinKeywordIndex).toLowerCase(Locale.ROOT);
        if (prefix.matches("(?is).*\\bleft\\s+semi\\s+$")) {
            return new JoinTypeInfo(5, "LEFT_SEMI_JOIN");
        }
        if (prefix.matches("(?is).*\\bleft\\s+anti\\s+$")) {
            return new JoinTypeInfo(6, "LEFT_ANTI_JOIN");
        }
        if (prefix.matches("(?is).*\\bleft\\s+(outer\\s+)?$")) {
            return new JoinTypeInfo(2, "LEFT_JOIN");
        }
        if (prefix.matches("(?is).*\\bright\\s+(outer\\s+)?$")) {
            return new JoinTypeInfo(3, "RIGHT_JOIN");
        }
        if (prefix.matches("(?is).*\\bfull\\s+(outer\\s+)?$")) {
            return new JoinTypeInfo(4, "FULL_JOIN");
        }
        if (prefix.matches("(?is).*\\bcross\\s+$")) {
            return new JoinTypeInfo(7, "CROSS_JOIN");
        }
        return new JoinTypeInfo(1, "INNER_JOIN");
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
        for (String partitionItem : splitSelectItems(matcher.group(1))) {
            if (!partitionItem.contains("=")) {
                String column = partitionItem.trim();
                if (!column.isEmpty()) {
                    dynamicColumns.add(column);
                }
            }
        }
        return dynamicColumns;
    }

    private void registerTemporaryTable(
        StatementLineageResult temporaryStatement,
        Map<String, List<DataObjectRef>> temporaryTableSources,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns
    ) {
        String temporaryTableName = temporaryStatement.getOutputObject().getQualifiedName();
        temporaryTableSources.put(temporaryTableName, temporaryStatement.getInputObjects());
        temporaryTableColumns.put(temporaryTableName, columnProjectionMap(temporaryStatement.getColumnLineages()));
        if (temporaryTableName.contains(".")) {
            String simpleName = temporaryTableName.substring(temporaryTableName.lastIndexOf('.') + 1);
            temporaryTableSources.put(simpleName, temporaryStatement.getInputObjects());
            temporaryTableColumns.put(simpleName, columnProjectionMap(temporaryStatement.getColumnLineages()));
        }
    }

    private Map<String, ColumnLineageProjection> columnProjectionMap(List<ColumnLineage> columnLineages) {
        Map<String, ColumnLineageProjection> projections = new HashMap<>();
        for (ColumnLineage columnLineage : columnLineages) {
            String columnName = columnNameOf(columnLineage.getTargetColumn().getQualifiedName());
            if (columnName == null) {
                continue;
            }
            projections.put(
                columnName.toLowerCase(Locale.ROOT),
                new ColumnLineageProjection(
                    columnLineage.getSourceColumns(),
                    columnLineage.getExpression(),
                    columnLineage.getLineageType()
                )
            );
        }
        return projections;
    }

    private boolean containsTemporaryTable(
        List<DataObjectRef> inputObjects,
        Map<String, List<DataObjectRef>> temporaryTableSources
    ) {
        for (DataObjectRef inputObject : inputObjects) {
            if (temporaryTableSources.containsKey(inputObject.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private List<DataObjectRef> expandTemporaryInputObjects(
        List<DataObjectRef> inputObjects,
        Map<String, List<DataObjectRef>> temporaryTableSources
    ) {
        List<DataObjectRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            List<DataObjectRef> temporarySources = temporaryTableSources.get(inputObject.getQualifiedName());
            if (temporarySources == null) {
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            for (DataObjectRef temporarySource : temporarySources) {
                addInputObject(expanded, seen, temporarySource);
            }
        }
        return expanded;
    }

    private List<DataObjectRef> expandViewInputObjects(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        int depth,
        Set<String> visitingViews,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (!request.getOptions().isExpandViewLineage()
            || request.getMetadataProvider() == null) {
            return inputObjects;
        }
        if (depth >= request.getOptions().getMaxViewExpandDepth()) {
            warnForBoundaryViews(request, inputObjects, warnings, statementIndex);
            return inputObjects;
        }

        List<DataObjectRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata == null || !metadata.isView()) {
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            if (metadata.getViewOriginalSql() == null || metadata.getViewOriginalSql().trim().isEmpty()) {
                addWarning(warnings, WarningCode.VIEW_METADATA_NOT_FOUND, "View definition SQL is missing for " + inputObject.getQualifiedName(), statementIndex);
                addInputObject(expanded, seen, inputObject);
                continue;
            }

            String viewName = inputObject.getQualifiedName();
            if (!visitingViews.add(viewName)) {
                addWarning(warnings, WarningCode.VIEW_EXPANSION_CYCLE, "View expansion cycle detected at " + viewName, statementIndex);
                addInputObject(expanded, seen, inputObject);
                continue;
            }
            List<DataObjectRef> viewSources;
            try {
                viewSources = parserAdapter.parse(metadata.getViewOriginalSql()).getInputObjects();
            } catch (RuntimeException ex) {
                addWarning(warnings, WarningCode.VIEW_EXPANSION_FAILED, "View expansion failed for " + viewName + ": " + ex.getMessage(), statementIndex);
                addInputObject(expanded, seen, inputObject);
                visitingViews.remove(viewName);
                continue;
            }
            viewSources = expandViewInputObjects(request, viewSources, depth + 1, visitingViews, warnings, statementIndex);
            for (DataObjectRef viewSource : viewSources) {
                addInputObject(expanded, seen, viewSource);
            }
            visitingViews.remove(viewName);
        }
        return expanded;
    }

    private List<ColumnRef> expandViewSourceColumns(
        LineageAnalyzeRequest request,
        List<ColumnRef> sourceColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        int depth,
        Set<String> visitingViews,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (!request.getOptions().isExpandViewLineage()
            || request.getMetadataProvider() == null) {
            return sourceColumns;
        }
        if (depth >= request.getOptions().getMaxViewExpandDepth()) {
            warnForBoundaryViewColumns(request, sourceColumns, warnings, statementIndex);
            return sourceColumns;
        }

        List<ColumnRef> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef sourceColumn : sourceColumns) {
            String tableName = tableNameOfColumn(sourceColumn.getQualifiedName());
            String columnName = columnNameOf(sourceColumn.getQualifiedName());
            if (tableName == null || columnName == null) {
                addColumn(expanded, seen, sourceColumn);
                continue;
            }

            HiveTableMetadata metadata = loadTableMetadata(request, tableName, warnings, statementIndex);
            if (metadata == null || !metadata.isView()) {
                addColumn(expanded, seen, sourceColumn);
                continue;
            }
            if (metadata.getViewOriginalSql() == null || metadata.getViewOriginalSql().trim().isEmpty()) {
                addWarning(warnings, WarningCode.VIEW_METADATA_NOT_FOUND, "View definition SQL is missing for " + tableName, statementIndex);
                addColumn(expanded, seen, sourceColumn);
                continue;
            }
            if (!visitingViews.add(tableName)) {
                addWarning(warnings, WarningCode.VIEW_EXPANSION_CYCLE, "View expansion cycle detected at " + tableName, statementIndex);
                addColumn(expanded, seen, sourceColumn);
                continue;
            }

            Map<String, ColumnLineageProjection> mapping = viewColumnMapping(request, metadata, viewColumnLineageCache, depth, visitingViews, warnings, statementIndex);
            ColumnLineageProjection mappedProjection = mapping.get(columnName.toLowerCase(Locale.ROOT));
            if (mappedProjection == null || mappedProjection.getSourceColumns().isEmpty()) {
                addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to map view column " + sourceColumn.getQualifiedName(), statementIndex);
                addColumn(expanded, seen, sourceColumn);
            } else {
                List<ColumnRef> recursivelyExpanded = expandViewSourceColumns(
                    request,
                    mappedProjection.getSourceColumns(),
                    viewColumnLineageCache,
                    depth + 1,
                    visitingViews,
                    warnings,
                    statementIndex
                );
                for (ColumnRef mappedColumn : recursivelyExpanded) {
                    addColumn(expanded, seen, mappedColumn);
                }
            }
            visitingViews.remove(tableName);
        }
        return expanded;
    }

    private Map<String, ColumnLineageProjection> viewColumnMapping(
        LineageAnalyzeRequest request,
        HiveTableMetadata viewMetadata,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        int depth,
        Set<String> visitingViews,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        String viewName = viewMetadata.getTableIdentifier().getQualifiedName();
        Map<String, ColumnLineageProjection> cached = viewColumnLineageCache.get(viewName);
        if (cached != null) {
            return cached;
        }

        ParsedHiveStatement parsedView;
        try {
            parsedView = parserAdapter.parse(viewMetadata.getViewOriginalSql());
        } catch (RuntimeException ex) {
            addWarning(warnings, WarningCode.VIEW_EXPANSION_FAILED, "View expansion failed for " + viewName + ": " + ex.getMessage(), statementIndex);
            return Collections.emptyMap();
        }
        List<String> targetColumns = parsedView.getTargetColumns();
        if (targetColumns.isEmpty() && viewMetadata.getColumns().size() >= parsedView.getSelectExpressions().size()) {
            targetColumns = viewMetadata.getColumns().subList(0, parsedView.getSelectExpressions().size());
        }

        List<DataObjectRef> viewInputs = expandViewInputObjects(request, parsedView.getInputObjects(), depth + 1, visitingViews, warnings, statementIndex);
        String defaultSourceTable = parsedView.getInputObjects().isEmpty()
            ? viewName
            : parsedView.getInputObjects().get(0).getQualifiedName();
        Map<String, String> sourceAliases = new HashMap<>(parsedView.getSourceAliases());
        Map<String, String> columnOwners = buildColumnOwners(request, viewInputs, request.getOptions().getParseMode(), warnings, statementIndex);

        Map<String, ColumnLineageProjection> mapping = new HashMap<>();
        int count = Math.min(targetColumns.size(), parsedView.getSelectExpressions().size());
        for (int i = 0; i < count; i++) {
            String expression = stripAlias(parsedView.getSelectExpressions().get(i));
            String lineageExpression = normalizeLineageExpression(expression);
            List<ColumnRef> sourceColumns = extractSourceColumns(
                expression,
                defaultSourceTable,
                sourceAliases,
                columnOwners,
                parsedView.getColumnAliasSources()
            );
            sourceColumns = expandViewSourceColumns(request, sourceColumns, viewColumnLineageCache, depth + 1, visitingViews, warnings, statementIndex);
            LineageType lineageType = resolveLineageType(lineageExpression, expression, sourceColumns);
            mapping.put(
                targetColumns.get(i).toLowerCase(Locale.ROOT),
                new ColumnLineageProjection(sourceColumns, lineageExpression, lineageType)
            );
        }
        viewColumnLineageCache.put(viewName, mapping);
        return mapping;
    }

    private void warnForBoundaryViews(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (DataObjectRef inputObject : inputObjects) {
            String tableName = inputObject.getQualifiedName();
            if (seen.add(tableName) && isKnownView(request, tableName)) {
                addWarning(warnings, WarningCode.VIEW_EXPANSION_DEPTH_EXCEEDED, "View expansion depth exceeded at " + tableName, statementIndex);
            }
        }
    }

    private void warnForBoundaryViewColumns(
        LineageAnalyzeRequest request,
        List<ColumnRef> sourceColumns,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef sourceColumn : sourceColumns) {
            String tableName = tableNameOfColumn(sourceColumn.getQualifiedName());
            if (tableName != null && seen.add(tableName) && isKnownView(request, tableName)) {
                addWarning(warnings, WarningCode.VIEW_EXPANSION_DEPTH_EXCEEDED, "View expansion depth exceeded at " + tableName, statementIndex);
            }
        }
    }

    private boolean isKnownView(LineageAnalyzeRequest request, String tableName) {
        if (request.getMetadataProvider() == null) {
            return false;
        }
        TableIdentifier tableIdentifier = TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase());
        HiveTableMetadata metadata = request.getMetadataCache().getTableMetadata(tableIdentifier, request.getMetadataProvider());
        return metadata != null && metadata.isView();
    }

    private void addInputObject(List<DataObjectRef> inputObjects, Set<String> seen, DataObjectRef inputObject) {
        if (seen.add(inputObject.getQualifiedName())) {
            inputObjects.add(inputObject);
        }
    }

    private void addColumn(List<ColumnRef> columns, Set<String> seen, ColumnRef column) {
        if (seen.add(column.getQualifiedName())) {
            columns.add(column);
        }
    }

    private String tableNameOfColumn(String qualifiedColumnName) {
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex < 0 ? null : qualifiedColumnName.substring(0, dotIndex);
    }

    private String columnNameOf(String qualifiedColumnName) {
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex < 0 ? null : qualifiedColumnName.substring(dotIndex + 1);
    }

    private String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
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
            CteExtraction cteExtraction = extractCtes(statement);
            if (cteExtraction.getRootSql().toLowerCase(Locale.ROOT).startsWith("select ")) {
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

    private boolean isSetStatement(String statement) {
        return statement.trim().toLowerCase(Locale.ROOT).startsWith("set ");
    }

    private void applySetStatement(String statement, Map<String, String> sessionVariables) {
        String assignment = statement.trim().substring(3).trim();
        int equalsIndex = assignment.indexOf('=');
        if (equalsIndex < 1) {
            return;
        }
        String key = assignment.substring(0, equalsIndex).trim();
        String value = stripQuotedValue(assignment.substring(equalsIndex + 1).trim());
        sessionVariables.put(key, value);
        sessionVariables.put("hiveconf:" + key, value);
        sessionVariables.put("hivevar:" + key, value);
    }

    private String stripQuotedValue(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private String resolveVariables(String statement, Map<String, String> sessionVariables, String fallbackVariableValue) {
        return resolveVariables(
            statement,
            sessionVariables,
            fallbackVariableValue,
            new ArrayList<LineageWarning>(),
            -1
        );
    }

    private String resolveVariables(
        String statement,
        Map<String, String> sessionVariables,
        String fallbackVariableValue,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        String resolved = statement;
        for (Map.Entry<String, String> entry : sessionVariables.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        resolved = replaceUnresolvedVariables(resolved, Pattern.compile("\\$\\{[^}]+}"), fallbackVariableValue, warnings, statementIndex);
        return replaceUnresolvedVariables(resolved, Pattern.compile("@\\{[^}]+}"), fallbackVariableValue, warnings, statementIndex);
    }

    private String replaceUnresolvedVariables(
        String statement,
        Pattern variablePattern,
        String fallbackVariableValue,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Matcher matcher = variablePattern.matcher(statement);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            addWarning(warnings, WarningCode.UNRESOLVED_VARIABLE, "Unresolved variable " + matcher.group(), statementIndex);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(fallbackVariableValue));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private List<String> splitStatements(String rawScript) {
        if (rawScript == null || rawScript.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < rawScript.length(); i++) {
            char ch = rawScript.charAt(i);
            char next = i + 1 < rawScript.length() ? rawScript.charAt(i + 1) : '\0';

            if (inLineComment) {
                current.append(ch);
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote && ch == '-' && next == '-') {
                current.append(ch).append(next);
                i++;
                inLineComment = true;
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote && ch == '/' && next == '*') {
                current.append(ch).append(next);
                i++;
                inBlockComment = true;
                continue;
            }

            if (!inDoubleQuote && ch == '\'') {
                current.append(ch);
                if (inSingleQuote && next == '\'') {
                    current.append(next);
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (!inSingleQuote && ch == '"') {
                current.append(ch);
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (!inSingleQuote && !inDoubleQuote && ch == ';') {
                addStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        addStatement(statements, current);
        return statements;
    }

    private String stripLeadingComments(String statement) {
        String remaining = statement == null ? "" : statement;
        boolean changed = true;
        while (changed) {
            changed = false;
            remaining = remaining.trim();
            if (remaining.startsWith("--")) {
                int lineEnd = indexOfLineEnd(remaining);
                remaining = lineEnd < 0 ? "" : remaining.substring(lineEnd + 1);
                changed = true;
            } else if (remaining.startsWith("/*")) {
                int blockEnd = remaining.indexOf("*/");
                remaining = blockEnd < 0 ? "" : remaining.substring(blockEnd + 2);
                changed = true;
            }
        }
        return remaining;
    }

    private int indexOfLineEnd(String value) {
        int newline = value.indexOf('\n');
        int carriageReturn = value.indexOf('\r');
        if (newline < 0) {
            return carriageReturn;
        }
        if (carriageReturn < 0) {
            return newline;
        }
        return Math.min(newline, carriageReturn);
    }

    private void addStatement(List<String> statements, StringBuilder statement) {
        String trimmed = statement.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
    }

    private List<String> parseTargetColumns(String rawColumns) {
        if (rawColumns == null || rawColumns.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> columns = new ArrayList<>();
        for (String column : rawColumns.split(",")) {
            columns.add(column.trim());
        }
        return columns;
    }

    private List<String> inferTargetColumns(List<String> selectItems) {
        List<String> columns = new ArrayList<>();
        for (String item : selectItems) {
            String alias = extractAlias(item);
            columns.add(alias == null ? stripAlias(item) : alias);
        }
        return columns;
    }

    private List<String> resolveTargetColumns(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> selectItems,
        LineageParseMode parseMode
    ) {
        return resolveTargetColumns(
            request,
            targetTable,
            selectItems,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    private List<String> resolveTargetColumns(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> selectItems,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (parseMode == LineageParseMode.COLUMN_ONLY || parseMode == LineageParseMode.TABLE_AND_COLUMN) {
            HiveTableMetadata metadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
            if (metadata != null && metadata.getColumns().size() >= selectItems.size()) {
                return new ArrayList<>(metadata.getColumns().subList(0, selectItems.size()));
            }
        }
        return inferTargetColumns(selectItems);
    }

    private List<String> expandStarSelectItems(
        LineageAnalyzeRequest request,
        List<String> selectItems,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode
    ) {
        return expandStarSelectItems(
            request,
            null,
            selectItems,
            inputObjects,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    private List<String> expandStarSelectItems(
        LineageAnalyzeRequest request,
        String targetTable,
        List<String> selectItems,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if ((parseMode != LineageParseMode.COLUMN_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN)
            || selectItems.size() != 1
            || !"*".equals(selectItems.get(0).trim())
            || inputObjects.size() != 1) {
            return selectItems;
        }
        if (targetTable != null) {
            HiveTableMetadata targetMetadata = loadTableMetadata(request, targetTable, warnings, statementIndex);
            if (targetMetadata != null && !targetMetadata.getColumns().isEmpty()) {
                HiveTableMetadata sourceMetadata = loadTableMetadataWithoutWarning(request, inputObjects.get(0).getQualifiedName());
                if (sourceMetadata != null && !sourceMetadata.getColumns().isEmpty()) {
                    List<String> intersection = intersectionInTargetOrder(targetMetadata.getColumns(), sourceMetadata.getColumns());
                    if (!intersection.isEmpty()) {
                        return intersection;
                    }
                }
                return new ArrayList<>(targetMetadata.getColumns());
            }
        }
        HiveTableMetadata sourceMetadata = loadTableMetadata(request, inputObjects.get(0).getQualifiedName(), warnings, statementIndex);
        if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
            return selectItems;
        }
        return new ArrayList<>(sourceMetadata.getColumns());
    }

    private boolean isSingleStarSelect(List<String> selectItems) {
        return selectItems.size() == 1 && "*".equals(selectItems.get(0).trim());
    }

    private List<String> intersectionInTargetOrder(List<String> targetColumns, List<String> sourceColumns) {
        Set<String> sourceColumnSet = new LinkedHashSet<>();
        for (String sourceColumn : sourceColumns) {
            sourceColumnSet.add(sourceColumn.toLowerCase(Locale.ROOT));
        }
        List<String> intersection = new ArrayList<>();
        for (String targetColumn : targetColumns) {
            if (sourceColumnSet.contains(targetColumn.toLowerCase(Locale.ROOT))) {
                intersection.add(targetColumn);
            }
        }
        return intersection;
    }

    private HiveTableMetadata loadTableMetadataWithoutWarning(LineageAnalyzeRequest request, String tableName) {
        if (request.getMetadataProvider() == null) {
            return null;
        }
        TableIdentifier tableIdentifier = TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase());
        return request.getMetadataCache().getTableMetadata(tableIdentifier, request.getMetadataProvider());
    }

    private HiveTableMetadata loadTableMetadata(LineageAnalyzeRequest request, String tableName) {
        return loadTableMetadata(request, tableName, Collections.<LineageWarning>emptyList(), -1);
    }

    private HiveTableMetadata loadTableMetadata(
        LineageAnalyzeRequest request,
        String tableName,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (request.getMetadataProvider() == null) {
            return null;
        }
        TableIdentifier tableIdentifier = TableIdentifier.parse(tableName, request.getClusterName(), request.getDefaultDatabase());
        HiveTableMetadata metadata = request.getMetadataCache().getTableMetadata(tableIdentifier, request.getMetadataProvider());
        if (metadata == null) {
            addWarning(warnings, WarningCode.METADATA_NOT_FOUND, "Metadata not found for " + tableIdentifier.getQualifiedName(), statementIndex);
        }
        return metadata;
    }

    private Map<String, String> extractSourceAliases(String normalizedStatement) {
        Map<String, String> aliases = new HashMap<>();
        Matcher objectMatcher = SOURCE_OBJECT.matcher(normalizedStatement);
        while (objectMatcher.find()) {
            String tableName = normalizeIdentifier(objectMatcher.group(1));
            String simpleTableName = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
            aliases.put(tableName, tableName);
            aliases.put(simpleTableName, tableName);
        }
        Matcher aliasMatcher = SOURCE_ALIAS.matcher(normalizedStatement);
        while (aliasMatcher.find()) {
            String tableName = normalizeIdentifier(aliasMatcher.group(1));
            String alias = aliasMatcher.group(2);
            if (alias != null && !isAliasKeyword(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), tableName);
            }
        }
        return aliases;
    }

    private void mergeTopLevelSourceAliases(Map<String, String> sourceAliases, Map<String, String> topLevelAliases) {
        for (Map.Entry<String, String> alias : topLevelAliases.entrySet()) {
            if (alias.getValue().contains(".") || !sourceAliases.containsKey(alias.getKey())) {
                sourceAliases.put(alias.getKey(), alias.getValue());
            }
        }
    }

    private Map<String, String> filterColumnAliasSources(
        Map<String, String> columnAliasSources,
        Map<String, String> topLevelAliases
    ) {
        Set<String> topLevelPhysicalAliases = new LinkedHashSet<>();
        for (Map.Entry<String, String> alias : topLevelAliases.entrySet()) {
            if (alias.getValue().contains(".") && !alias.getKey().contains(".")) {
                topLevelPhysicalAliases.add(alias.getKey().toLowerCase(Locale.ROOT));
            }
        }
        if (topLevelPhysicalAliases.isEmpty()) {
            return columnAliasSources;
        }
        Map<String, String> filtered = new HashMap<>();
        for (Map.Entry<String, String> source : columnAliasSources.entrySet()) {
            int dotIndex = source.getKey().indexOf('.');
            String owner = dotIndex < 0 ? source.getKey() : source.getKey().substring(0, dotIndex);
            if (!topLevelPhysicalAliases.contains(owner.toLowerCase(Locale.ROOT))) {
                filtered.put(source.getKey(), source.getValue());
            }
        }
        return filtered;
    }

    private Map<String, String> extractTopLevelSourceAliases(String normalizedStatement) {
        Map<String, String> aliases = new HashMap<>();
        int start = lastTopLevelKeywordIndex(normalizedStatement, "insert");
        if (start < 0) {
            start = lastTopLevelKeywordIndex(normalizedStatement, "select");
        }
        if (start < 0) {
            start = 0;
        }
        int depth = 0;
        char quote = 0;
        for (int i = start; i < normalizedStatement.length(); i++) {
            char ch = normalizedStatement.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && (matchesKeywordAt(normalizedStatement, i, "from") || matchesKeywordAt(normalizedStatement, i, "join"))) {
                i = captureTopLevelSourceAlias(normalizedStatement, i, aliases);
            }
        }
        return aliases;
    }

    private int captureTopLevelSourceAlias(String statement, int keywordIndex, Map<String, String> aliases) {
        int position = skipWhitespace(statement, keywordIndex);
        position = readIdentifier(statement, position);
        position = skipWhitespace(statement, position);
        if (position >= statement.length() || statement.charAt(position) == '(') {
            return Math.max(keywordIndex, position - 1);
        }
        int tableStart = position;
        int tableEnd = readIdentifier(statement, tableStart);
        if (tableEnd == tableStart) {
            return position;
        }
        String tableName = normalizeIdentifier(statement.substring(tableStart, tableEnd));
        String simpleTableName = tableName.contains(".") ? tableName.substring(tableName.lastIndexOf('.') + 1) : tableName;
        aliases.put(tableName, tableName);
        aliases.put(simpleTableName, tableName);

        position = skipWhitespace(statement, tableEnd);
        int aliasStart = position;
        int aliasEnd = readIdentifier(statement, aliasStart);
        if (aliasEnd > aliasStart && "as".equalsIgnoreCase(statement.substring(aliasStart, aliasEnd))) {
            aliasStart = skipWhitespace(statement, aliasEnd);
            aliasEnd = readIdentifier(statement, aliasStart);
        }
        if (aliasEnd > aliasStart) {
            String alias = statement.substring(aliasStart, aliasEnd);
            if (!isAliasKeyword(alias)) {
                aliases.put(alias.toLowerCase(Locale.ROOT), tableName);
            }
        }
        return tableEnd;
    }

    private int lastTopLevelKeywordIndex(String statement, String keyword) {
        int found = -1;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < statement.length(); i++) {
            char ch = statement.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && matchesKeywordAt(statement, i, keyword)) {
                found = i;
            }
        }
        return found;
    }

    private boolean matchesKeywordAt(String statement, int index, String keyword) {
        int end = index + keyword.length();
        if (index < 0 || end > statement.length()) {
            return false;
        }
        if (!statement.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        return (index == 0 || !isIdentifierCharacter(statement.charAt(index - 1)))
            && (end == statement.length() || !isIdentifierCharacter(statement.charAt(end)));
    }

    private int skipWhitespace(String statement, int position) {
        while (position < statement.length() && Character.isWhitespace(statement.charAt(position))) {
            position++;
        }
        return position;
    }

    private int readIdentifier(String statement, int position) {
        while (position < statement.length() && isIdentifierCharacter(statement.charAt(position))) {
            position++;
        }
        return position;
    }

    private boolean isIdentifierCharacter(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.';
    }

    private Map<String, String> buildColumnOwners(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode
    ) {
        return buildColumnOwners(
            request,
            inputObjects,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    private Map<String, String> buildColumnOwners(
        LineageAnalyzeRequest request,
        List<DataObjectRef> inputObjects,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        Map<String, String> columnOwners = new HashMap<>();
        if (inputObjects.size() <= 1 || (parseMode != LineageParseMode.COLUMN_ONLY && parseMode != LineageParseMode.TABLE_AND_COLUMN)) {
            return columnOwners;
        }
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata == null) {
                continue;
            }
            for (String column : metadata.getColumns()) {
                String normalizedColumn = column.toLowerCase(Locale.ROOT);
                if (!columnOwners.containsKey(normalizedColumn)) {
                    columnOwners.put(normalizedColumn, inputObject.getQualifiedName());
                }
            }
        }
        return columnOwners;
    }

    private List<ColumnRef> setOperationSourceColumns(ParsedHiveStatement parsedStatement, int selectIndex) {
        List<String> sourceColumns = parsedStatement.getSetOperationSourceColumnsBySelectIndex().get(selectIndex);
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return Collections.emptyList();
        }
        List<ColumnRef> refs = new ArrayList<>();
        for (String sourceColumn : sourceColumns) {
            refs.add(new ColumnRef(sourceColumn));
        }
        return refs;
    }

    private String setOperationExpression(ParsedHiveStatement parsedStatement, String normalizedStatement, int selectIndex) {
        List<String> expressions = parsedStatement.getSetOperationExpressionsBySelectIndex().get(selectIndex);
        if (expressions == null || expressions.isEmpty()) {
            return "";
        }
        if (expressions.size() == 1) {
            return expressions.get(0);
        }
        return setOperationFunctionName(normalizedStatement) + "(" + joinWithComma(expressions) + ")";
    }

    private String setOperationFunctionName(String normalizedStatement) {
        String statement = normalizedStatement == null ? "" : normalizedStatement;
        if (Pattern.compile("(?i)\\bunion\\s+all\\b").matcher(statement).find()) {
            return "union_all";
        }
        if (Pattern.compile("(?i)\\bunion\\b").matcher(statement).find()) {
            return "union_distinct";
        }
        if (Pattern.compile("(?i)\\bintersect\\b").matcher(statement).find()) {
            return "intersect";
        }
        if (Pattern.compile("(?i)\\bexcept\\b").matcher(statement).find()) {
            return "except";
        }
        return "set_operation";
    }

    private String joinWithComma(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private boolean hasCompleteSetOperationProjection(ParsedHiveStatement parsedStatement, int selectItemCount) {
        Map<Integer, List<String>> expressions = parsedStatement.getSetOperationExpressionsBySelectIndex();
        if (selectItemCount <= 0 || expressions.size() != selectItemCount) {
            return false;
        }
        for (int i = 0; i < selectItemCount; i++) {
            List<String> positionExpressions = expressions.get(i);
            if (positionExpressions == null || positionExpressions.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private List<ColumnRef> extractSetOperationSourceColumns(
        LineageAnalyzeRequest request,
        String expression,
        List<DataObjectRef> inputObjects,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (!isDirectExpression(expression) || expression.contains(".") || inputObjects.size() <= 1) {
            return Collections.emptyList();
        }
        String normalizedColumn = expression.toLowerCase(Locale.ROOT);
        List<ColumnRef> columns = new ArrayList<>();
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata == null || containsColumn(metadata, normalizedColumn)) {
                columns.add(new ColumnRef(inputObject.getQualifiedName() + "." + expression));
            }
        }
        return columns;
    }

    private ColumnLineageProjection resolveDirectColumnProjection(
        LineageAnalyzeRequest request,
        String expression,
        List<DataObjectRef> originalInputObjects,
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        DirectColumn directColumn = parseDirectColumn(expression);
        if (directColumn == null) {
            return null;
        }
        String tableName = null;
        if (directColumn.getOwner() != null) {
            tableName = sourceAliases.get(directColumn.getOwner());
            if (tableName == null) {
                tableName = directColumn.getOwner();
            }
        } else if (originalInputObjects.size() == 1) {
            tableName = originalInputObjects.get(0).getQualifiedName();
        }
        if (tableName == null) {
            return null;
        }
        ColumnLineageProjection temporaryProjection = columnProjection(tableName, directColumn.getColumn(), temporaryTableColumns);
        if (temporaryProjection != null) {
            return temporaryProjection;
        }
        return viewColumnProjection(request, tableName, directColumn.getColumn(), viewColumnLineageCache, warnings, statementIndex);
    }

    private ColumnLineageProjection columnProjection(
        String tableName,
        String columnName,
        Map<String, Map<String, ColumnLineageProjection>> projectionsByTable
    ) {
        Map<String, ColumnLineageProjection> projections = projectionsByTable.get(tableName);
        if (projections == null && tableName.contains(".")) {
            projections = projectionsByTable.get(tableName.substring(tableName.lastIndexOf('.') + 1));
        }
        return projections == null ? null : projections.get(columnName.toLowerCase(Locale.ROOT));
    }

    private ColumnLineageProjection viewColumnProjection(
        LineageAnalyzeRequest request,
        String tableName,
        String columnName,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (!request.getOptions().isExpandViewLineage() || request.getMetadataProvider() == null) {
            return null;
        }
        HiveTableMetadata metadata = loadTableMetadata(request, tableName, warnings, statementIndex);
        if (metadata == null || !metadata.isView() || metadata.getViewOriginalSql() == null || metadata.getViewOriginalSql().trim().isEmpty()) {
            return null;
        }
        Map<String, ColumnLineageProjection> mapping = viewColumnMapping(
            request,
            metadata,
            viewColumnLineageCache,
            0,
            new LinkedHashSet<String>(),
            warnings,
            statementIndex
        );
        return mapping.get(columnName.toLowerCase(Locale.ROOT));
    }

    private String directColumnAliasExpression(String expression, Map<String, String> columnAliasExpressions) {
        DirectColumn directColumn = parseDirectColumn(expression);
        if (directColumn == null) {
            return null;
        }
        if (directColumn.getOwner() != null) {
            return columnAliasExpressions.get((directColumn.getOwner() + "." + directColumn.getColumn()).toLowerCase(Locale.ROOT));
        }
        return columnAliasExpressions.get(directColumn.getColumn().toLowerCase(Locale.ROOT));
    }

    private DirectColumn parseDirectColumn(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        if (!isDirectExpression(trimmed)) {
            return null;
        }
        int dotIndex = trimmed.indexOf('.');
        if (dotIndex < 0) {
            return new DirectColumn(null, trimmed.toLowerCase(Locale.ROOT));
        }
        return new DirectColumn(
            trimmed.substring(0, dotIndex).toLowerCase(Locale.ROOT),
            trimmed.substring(dotIndex + 1).toLowerCase(Locale.ROOT)
        );
    }

    private boolean containsColumn(HiveTableMetadata metadata, String normalizedColumn) {
        for (String column : metadata.getColumns()) {
            if (normalizedColumn.equals(column.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitSelectItems(String selectList) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < selectList.length(); i++) {
            char ch = selectList.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                items.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            items.add(current.toString().trim());
        }
        return items;
    }

    private String stripAlias(String selectItem) {
        int asIndex = topLevelAsIndex(selectItem);
        if (asIndex < 0) {
            return selectItem.trim();
        }
        return selectItem.substring(0, asIndex).trim();
    }

    private String extractAlias(String selectItem) {
        int asIndex = topLevelAsIndex(selectItem);
        if (asIndex < 0) {
            return null;
        }
        return selectItem.substring(asIndex + 4).trim();
    }

    private int topLevelAsIndex(String selectItem) {
        String lower = selectItem.toLowerCase(Locale.ROOT);
        int depth = 0;
        int found = -1;
        for (int i = 0; i <= lower.length() - 4; i++) {
            char ch = lower.charAt(i);
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && lower.startsWith(" as ", i)) {
                found = i;
            }
        }
        return found;
    }

    private boolean isDirectExpression(String expression) {
        return expression.matches("[a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)?");
    }

    private LineageType resolveLineageType(String lineageExpression, String rawExpression, List<ColumnRef> sourceColumns) {
        if (isConstantLineageExpression(lineageExpression)) {
            return LineageType.CONSTANT;
        }
        if (lineageExpression != null && isSetOperationLineageExpression(lineageExpression.trim())) {
            return LineageType.DERIVED;
        }
        if (isDirectExpression(rawExpression)
            && sourceColumns.size() == 1
            && normalizeLineageExpression(rawExpression).equals(lineageExpression)) {
            return LineageType.DIRECT;
        }
        return LineageType.DERIVED;
    }

    private String normalizeLineageExpression(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        return isLiteralExpression(trimmed) ? "constant(" + trimmed + ")" : trimmed;
    }

    private boolean isConstantLineageExpression(String expression) {
        if (expression == null) {
            return false;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("constant(")) {
            return true;
        }
        String setOperationFunction = setOperationLineageFunction(trimmed);
        if (setOperationFunction != null && trimmed.endsWith(")")) {
            String inner = trimmed.substring(setOperationFunction.length() + 1, trimmed.length() - 1);
            List<String> parts = splitSelectItems(inner);
            if (parts.isEmpty()) {
                return false;
            }
            for (String part : parts) {
                if (!isConstantLineageExpression(part)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean isSetOperationLineageExpression(String expression) {
        return setOperationLineageFunction(expression) != null;
    }

    private String setOperationLineageFunction(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        String[] functions = {"union_all", "union_distinct", "intersect", "except", "set_operation"};
        for (String function : functions) {
            if (trimmed.startsWith(function + "(")) {
                return function;
            }
        }
        return null;
    }

    private boolean isLiteralExpression(String expression) {
        return expression.matches("'(?:[^']|'')*'")
            || expression.matches("\"(?:[^\"]|\"\")*\"")
            || expression.matches("[-+]?\\d+(?:\\.\\d+)?")
            || "true".equalsIgnoreCase(expression)
            || "false".equalsIgnoreCase(expression)
            || "null".equalsIgnoreCase(expression);
    }

    private List<ColumnRef> extractSourceColumns(
        String expression,
        String defaultSourceTable,
        Map<String, String> sourceAliases,
        Map<String, String> columnOwners,
        Map<String, String> columnAliasSources
    ) {
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(removeStringLiterals(expression));
        while (matcher.find()) {
            String first = matcher.group(1);
            String second = matcher.group(2);
            if (isKeyword(first) || isFunctionName(expression, matcher.end())) {
                continue;
            }
            if (second == null) {
                String mappedSource = columnAliasSources.get(first.toLowerCase(Locale.ROOT));
                if (mappedSource != null) {
                    addMappedColumn(columns, mappedSource, defaultSourceTable, sourceAliases);
                    continue;
                }
                String owner = columnOwners.get(first.toLowerCase(Locale.ROOT));
                columns.add((owner == null ? defaultSourceTable : owner) + "." + first);
            } else {
                String alias = first.toLowerCase(Locale.ROOT);
                String mappedSource = columnAliasSources.get((alias + "." + second).toLowerCase(Locale.ROOT));
                if (mappedSource != null) {
                    addMappedColumn(columns, mappedSource, defaultSourceTable, sourceAliases);
                    continue;
                }
                String owner = sourceAliases.get(alias);
                columns.add((owner == null ? first : owner) + "." + second);
            }
        }
        List<ColumnRef> refs = new ArrayList<>();
        for (String column : columns) {
            refs.add(new ColumnRef(column));
        }
        return refs;
    }

    private void addMappedColumn(Set<String> columns, String mappedSource, String defaultSourceTable, Map<String, String> sourceAliases) {
        if (mappedSource.contains(".")) {
            int dotIndex = mappedSource.lastIndexOf('.');
            String owner = mappedSource.substring(0, dotIndex).toLowerCase(Locale.ROOT);
            String column = mappedSource.substring(dotIndex + 1);
            String resolvedOwner = sourceAliases.get(owner);
            columns.add((resolvedOwner == null ? owner : resolvedOwner) + "." + column);
            return;
        }
        columns.add(defaultSourceTable + "." + mappedSource);
    }

    private List<String> extractFunctionNames(String expression) {
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b([a-zA-Z_][\\w]*)\\s*\\(").matcher(expression);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!isKeyword(name)) {
                names.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private boolean isFunctionName(String expression, int tokenEnd) {
        int i = tokenEnd;
        while (i < expression.length() && Character.isWhitespace(expression.charAt(i))) {
            i++;
        }
        return i < expression.length() && expression.charAt(i) == '(';
    }

    private boolean mayContainColumnReference(String expression) {
        if (isLiteralExpression(expression == null ? "" : expression.trim())) {
            return false;
        }
        Matcher matcher = IDENTIFIER.matcher(removeStringLiterals(expression));
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!isKeyword(token) && !isFunctionName(expression, matcher.end())) {
                return true;
            }
        }
        return false;
    }

    private String removeStringLiterals(String expression) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (inSingleQuote) {
                if (ch == '\'' && i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                    builder.append(' ');
                    i++;
                    builder.append(' ');
                    continue;
                }
                if (ch == '\'') {
                    inSingleQuote = false;
                }
                builder.append(' ');
                continue;
            }
            if (inDoubleQuote) {
                if (ch == '"' && i + 1 < expression.length() && expression.charAt(i + 1) == '"') {
                    builder.append(' ');
                    i++;
                    builder.append(' ');
                    continue;
                }
                if (ch == '"') {
                    inDoubleQuote = false;
                }
                builder.append(' ');
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                builder.append(' ');
            } else if (ch == '"') {
                inDoubleQuote = true;
                builder.append(' ');
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private void addWarning(List<LineageWarning> warnings, WarningCode warningCode, String message, int statementIndex) {
        if (warnings == null) {
            return;
        }
        for (LineageWarning warning : warnings) {
            if (warning.getWarningCode() == warningCode
                && warning.getStatementIndex() == statementIndex
                && warning.getMessage().equals(message)) {
                return;
            }
        }
        warnings.add(new LineageWarning(warningCode, message, statementIndex));
    }

    private boolean isKeyword(String token) {
        return Arrays.asList(
            "case", "when", "then", "else", "end", "as", "null", "true", "false",
            "over", "partition", "by", "order", "asc", "desc", "rows", "range",
            "between", "preceding", "following", "current", "row", "unbounded",
            "nulls", "first", "last",
            "tinyint", "smallint", "int", "integer", "bigint", "float", "double", "decimal",
            "string", "char", "varchar", "boolean", "binary", "date", "timestamp",
            "array", "map", "struct", "uniontype"
        ).contains(token.toLowerCase(Locale.ROOT));
    }

    private boolean isAliasKeyword(String token) {
        return Arrays.asList(
            "on", "where", "left", "right", "inner", "outer", "full", "join", "union",
            "group", "order", "having", "limit", "cross", "semi"
        ).contains(token.toLowerCase(Locale.ROOT));
    }

    private static final class JoinIndexCounter {
        private int next = 1;

        private int next() {
            return next++;
        }
    }

    private static final class JoinClause {
        private final int joinKeywordIndex;
        private final String expression;

        private JoinClause(int joinKeywordIndex, String expression) {
            this.joinKeywordIndex = joinKeywordIndex;
            this.expression = expression;
        }

        private int getJoinKeywordIndex() {
            return joinKeywordIndex;
        }

        private String getExpression() {
            return expression;
        }
    }

    private static final class JoinTypeInfo {
        private final int code;
        private final String name;

        private JoinTypeInfo(int code, String name) {
            this.code = code;
            this.name = name;
        }

        private int getCode() {
            return code;
        }

        private String getName() {
            return name;
        }
    }

    private static final class CteBlock {
        private final String name;
        private final String sql;

        private CteBlock(String name, String sql) {
            this.name = name;
            this.sql = sql;
        }

        private String getName() {
            return name;
        }

        private String getSql() {
            return sql;
        }
    }

    private static final class CteExtraction {
        private final List<CteBlock> cteBlocks;
        private final String rootSql;

        private CteExtraction(List<CteBlock> cteBlocks, String rootSql) {
            this.cteBlocks = Collections.unmodifiableList(new ArrayList<>(cteBlocks));
            this.rootSql = rootSql;
        }

        private List<CteBlock> getCteBlocks() {
            return cteBlocks;
        }

        private String getRootSql() {
            return rootSql;
        }
    }

    private static final class ColumnLineageProjection {
        private final List<ColumnRef> sourceColumns;
        private final String expression;
        private final LineageType lineageType;

        private ColumnLineageProjection(List<ColumnRef> sourceColumns, String expression, LineageType lineageType) {
            this.sourceColumns = Collections.unmodifiableList(new ArrayList<>(sourceColumns));
            this.expression = expression;
            this.lineageType = lineageType;
        }

        private List<ColumnRef> getSourceColumns() {
            return sourceColumns;
        }

        private String getExpression() {
            return expression;
        }

        private LineageType getLineageType() {
            return lineageType;
        }
    }

    private static final class DirectColumn {
        private final String owner;
        private final String column;

        private DirectColumn(String owner, String column) {
            this.owner = owner;
            this.column = column;
        }

        private String getOwner() {
            return owner;
        }

        private String getColumn() {
            return column;
        }
    }
}
