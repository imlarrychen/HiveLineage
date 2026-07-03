package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
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
        Map<String, Map<String, List<ColumnRef>>> viewColumnLineageCache = new HashMap<>();
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
                viewColumnLineageCache,
                statementWarnings
            );
            if (Integer.valueOf(PARSE_STATUS_FAILED).equals(result.getParseStatusCode())) {
                results.add(result);
                failed++;
                continue;
            }
            if ("CREATE_TEMPORARY_TABLE_AS_SELECT".equals(result.getStatementType())) {
                registerTemporaryTable(result, temporaryTableSources);
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
        Map<String, Map<String, List<ColumnRef>>> viewColumnLineageCache,
        List<LineageWarning> statementWarnings
    ) {
        ParsedHiveStatement parsedStatement = parseStatement(normalizedStatement);
        if (isLineageStatement(parsedStatement) && !isCreateTemporaryTable(normalizedStatement)) {
            return analyzeSelectItemsToTarget(
                request,
                rawStatement,
                normalizedStatement,
                index,
                operationFor(parsedStatement, normalizedStatement),
                parsedStatement.getOutputObject().getQualifiedName(),
                parsedStatement.getTargetColumns(),
                parsedStatement.getSelectExpressions(),
                parsedStatement,
                temporaryTableSources,
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
        Map<String, Map<String, List<ColumnRef>>> viewColumnLineageCache,
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
        Map<String, Map<String, List<ColumnRef>>> viewColumnLineageCache,
        List<LineageWarning> statementWarnings
    ) {
        LineageParseMode parseMode = request.getOptions().getParseMode();

        DataObjectRef targetObject = new DataObjectRef(targetTable);
        ParsedHiveStatement resolvedParsedStatement = parsedStatement == null ? parserAdapter.parse(normalizedStatement) : parsedStatement;
        List<DataObjectRef> originalInputObjects = resolvedParsedStatement.getInputObjects();
        if (originalInputObjects.isEmpty()) {
            return failedResult(request, rawStatement, normalizedStatement, index, "SQL_PARSE_ERROR", "No input object found", statementWarnings);
        }
        selectItems = expandStarSelectItems(request, selectItems, originalInputObjects, parseMode, statementWarnings, index);

        if (targetColumns.isEmpty()) {
            targetColumns = resolveTargetColumns(request, targetTable, selectItems, parseMode, statementWarnings, index);
        }
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
            Map<String, String> columnOwners = buildColumnOwners(request, inputObjects, parseMode, statementWarnings, index);
            boolean setOperationQuery = isSetOperationQuery(normalizedStatement);
            for (int i = 0; i < selectItems.size(); i++) {
                String expression = stripAlias(selectItems.get(i));
                List<ColumnRef> sourceColumns = setOperationQuery
                    ? setOperationSourceColumns(resolvedParsedStatement, i)
                    : Collections.<ColumnRef>emptyList();
                if (sourceColumns.isEmpty() && setOperationQuery) {
                    sourceColumns = extractSetOperationSourceColumns(request, expression, columnReferenceInputObjects, statementWarnings, index);
                }
                if (sourceColumns.isEmpty()) {
                    sourceColumns = extractSourceColumns(
                        expression,
                        defaultColumnSourceTable,
                        sourceAliases,
                        columnOwners,
                        resolvedParsedStatement.getColumnAliasSources()
                    );
                }
                sourceColumns = expandViewSourceColumns(request, sourceColumns, viewColumnLineageCache, 0, new LinkedHashSet<String>(), statementWarnings, index);
                if (sourceColumns.isEmpty() && mayContainColumnReference(expression)) {
                    addWarning(statementWarnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to resolve source columns for expression: " + expression, index);
                }
                LineageType lineageType = isDirectExpression(expression) && sourceColumns.size() == 1
                    ? LineageType.DIRECT
                    : LineageType.DERIVED;
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + targetColumns.get(i)),
                    sourceColumns,
                    inputObjects,
                    expression,
                    true,
                    lineageType,
                    extractFunctionNames(expression),
                    lineageType == LineageType.DERIVED,
                    index
                ));
            }
            for (Map.Entry<String, String> partitionValue : resolvedParsedStatement.getStaticPartitionValues().entrySet()) {
                columnLineages.add(new ColumnLineage(
                    new ColumnRef(targetTable + "." + partitionValue.getKey()),
                    Collections.<ColumnRef>emptyList(),
                    inputObjects,
                    partitionValue.getValue(),
                    true,
                    LineageType.DERIVED,
                    Collections.<String>emptyList(),
                    false,
                    index
                ));
            }
        }

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
            statementWarnings
        );
    }

    private void registerTemporaryTable(
        StatementLineageResult temporaryStatement,
        Map<String, List<DataObjectRef>> temporaryTableSources
    ) {
        String temporaryTableName = temporaryStatement.getOutputObject().getQualifiedName();
        temporaryTableSources.put(temporaryTableName, temporaryStatement.getInputObjects());
        if (temporaryTableName.contains(".")) {
            temporaryTableSources.put(temporaryTableName.substring(temporaryTableName.lastIndexOf('.') + 1), temporaryStatement.getInputObjects());
        }
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
        Map<String, Map<String, List<ColumnRef>>> viewColumnLineageCache,
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

            Map<String, List<ColumnRef>> mapping = viewColumnMapping(request, metadata, viewColumnLineageCache, depth, visitingViews, warnings, statementIndex);
            List<ColumnRef> mappedColumns = mapping.get(columnName.toLowerCase(Locale.ROOT));
            if (mappedColumns == null || mappedColumns.isEmpty()) {
                addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to map view column " + sourceColumn.getQualifiedName(), statementIndex);
                addColumn(expanded, seen, sourceColumn);
            } else {
                List<ColumnRef> recursivelyExpanded = expandViewSourceColumns(
                    request,
                    mappedColumns,
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

    private Map<String, List<ColumnRef>> viewColumnMapping(
        LineageAnalyzeRequest request,
        HiveTableMetadata viewMetadata,
        Map<String, Map<String, List<ColumnRef>>> viewColumnLineageCache,
        int depth,
        Set<String> visitingViews,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        String viewName = viewMetadata.getTableIdentifier().getQualifiedName();
        Map<String, List<ColumnRef>> cached = viewColumnLineageCache.get(viewName);
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

        Map<String, List<ColumnRef>> mapping = new HashMap<>();
        int count = Math.min(targetColumns.size(), parsedView.getSelectExpressions().size());
        for (int i = 0; i < count; i++) {
            String expression = stripAlias(parsedView.getSelectExpressions().get(i));
            List<ColumnRef> sourceColumns = extractSourceColumns(
                expression,
                defaultSourceTable,
                sourceAliases,
                columnOwners,
                parsedView.getColumnAliasSources()
            );
            sourceColumns = expandViewSourceColumns(request, sourceColumns, viewColumnLineageCache, depth + 1, visitingViews, warnings, statementIndex);
            mapping.put(targetColumns.get(i).toLowerCase(Locale.ROOT), sourceColumns);
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
        if (lower.startsWith("create table ") && !lower.contains(" as select ")) {
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
            selectItems,
            inputObjects,
            parseMode,
            Collections.<LineageWarning>emptyList(),
            -1
        );
    }

    private List<String> expandStarSelectItems(
        LineageAnalyzeRequest request,
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
        HiveTableMetadata sourceMetadata = loadTableMetadata(request, inputObjects.get(0).getQualifiedName(), warnings, statementIndex);
        if (sourceMetadata == null || sourceMetadata.getColumns().isEmpty()) {
            return selectItems;
        }
        return new ArrayList<>(sourceMetadata.getColumns());
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

    private boolean isSetOperationQuery(String normalizedStatement) {
        return Pattern.compile("(?i)\\bunion\\b|\\bintersect\\b|\\bexcept\\b").matcher(normalizedStatement).find();
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
        String alias = extractAlias(selectItem);
        if (alias == null) {
            return selectItem.trim();
        }
        return selectItem.substring(0, selectItem.toLowerCase(Locale.ROOT).lastIndexOf(" as ")).trim();
    }

    private String extractAlias(String selectItem) {
        String lower = selectItem.toLowerCase(Locale.ROOT);
        int asIndex = lower.lastIndexOf(" as ");
        if (asIndex < 0) {
            return null;
        }
        return selectItem.substring(asIndex + 4).trim();
    }

    private boolean isDirectExpression(String expression) {
        return expression.matches("[a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)?");
    }

    private List<ColumnRef> extractSourceColumns(
        String expression,
        String defaultSourceTable,
        Map<String, String> sourceAliases,
        Map<String, String> columnOwners,
        Map<String, String> columnAliasSources
    ) {
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(expression);
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
                String owner = sourceAliases.get(first.toLowerCase(Locale.ROOT));
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
        Matcher matcher = IDENTIFIER.matcher(expression);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!isKeyword(token) && !isFunctionName(expression, matcher.end())) {
                return true;
            }
        }
        return false;
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
        return Arrays.asList("case", "when", "then", "else", "end", "as", "null", "true", "false").contains(token.toLowerCase(Locale.ROOT));
    }

    private boolean isAliasKeyword(String token) {
        return Arrays.asList(
            "on", "where", "left", "right", "inner", "outer", "full", "join", "union",
            "group", "order", "having", "limit", "cross", "semi"
        ).contains(token.toLowerCase(Locale.ROOT));
    }
}
