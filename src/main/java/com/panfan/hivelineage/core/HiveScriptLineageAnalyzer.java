package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
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
        "(?is)^create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z_][\\w.]*)"
            + ".*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern CREATE_TEMPORARY_TABLE_AS_SELECT = Pattern.compile(
        "(?is)^create\\s+temporary\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-zA-Z_][\\w.]*).*?\\bas\\s+select\\s+(.+?)\\s+from\\s+([a-zA-Z_][\\w.]*)(?:\\s+.*)?$"
    );
    private static final Pattern AS_WITH_SELECT = Pattern.compile("(?is)\\bas\\s+(?:with\\b.*)?select\\b");
    private static final Pattern INSERT_OVERWRITE_DIRECTORY = Pattern.compile(
        "(?is)^insert\\s+overwrite\\s+(local\\s+)?directory\\b.*"
    );
    private static final Pattern INSERT_VALUES = Pattern.compile(
        "(?is)^insert\\s+(overwrite\\s+table|into(?:\\s+table)?)\\s+.*\\s+values\\s*\\(.*"
    );
    private final HiveAstParserAdapter parserAdapter;
    private final HivePhysicalNameResolver nameResolver;
    private final HiveStatementDatabaseBinder databaseBinder;
    private final HiveScriptPreprocessor scriptPreprocessor = new HiveScriptPreprocessor();
    private final HiveTargetColumnResolver targetColumnResolver;
    private final HiveFallbackJoinSourceResolver fallbackJoinSourceResolver =
        new HiveFallbackJoinSourceResolver();
    private final HiveProjectionLineageResolver projectionResolver;
    private final HiveLineageExpansionService expansionService;
    private final HiveColumnLineageBuilder columnLineageBuilder;
    private final HiveJoinConditionAnalyzer joinAnalyzer;
    private final HiveSelectLineageStatementAnalyzer selectStatementAnalyzer;

    public HiveScriptLineageAnalyzer() {
        this(new FallbackHiveAstParserAdapter(new HiveParserAstAdapter(), new HeuristicHiveAstParserAdapter()));
    }

    public HiveScriptLineageAnalyzer(HiveAstParserAdapter parserAdapter) {
        this.parserAdapter = parserAdapter == null ? new HeuristicHiveAstParserAdapter() : parserAdapter;
        this.nameResolver = new HivePhysicalNameResolver();
        this.databaseBinder = new HiveStatementDatabaseBinder(nameResolver);
        this.targetColumnResolver = new HiveTargetColumnResolver(nameResolver);
        this.projectionResolver = new HiveProjectionLineageResolver(this.parserAdapter, targetColumnResolver);
        this.expansionService = new HiveLineageExpansionService(
            this.parserAdapter,
            targetColumnResolver,
            projectionResolver,
            nameResolver,
            databaseBinder
        );
        this.columnLineageBuilder = new HiveColumnLineageBuilder(
            targetColumnResolver,
            projectionResolver,
            expansionService
        );
        this.joinAnalyzer = new HiveJoinConditionAnalyzer(
            this.parserAdapter,
            targetColumnResolver,
            projectionResolver,
            expansionService,
            databaseBinder
        );
        HiveSelectLineageStatementAnalyzer.Dependencies selectDependencies =
            new HiveSelectLineageStatementAnalyzer.Dependencies();
        selectDependencies.parserAdapter = this.parserAdapter;
        selectDependencies.databaseBinder = databaseBinder;
        selectDependencies.targetColumnResolver = targetColumnResolver;
        selectDependencies.projectionResolver = projectionResolver;
        selectDependencies.expansionService = expansionService;
        selectDependencies.columnLineageBuilder = columnLineageBuilder;
        selectDependencies.joinAnalyzer = joinAnalyzer;
        this.selectStatementAnalyzer = new HiveSelectLineageStatementAnalyzer(selectDependencies);
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
                request.getOptions().getFallbackVariableValue()
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
            if (scriptPreprocessor.isUseDatabaseCandidate(normalized)) {
                UseDatabaseResolution useResolution = scriptPreprocessor.resolveUseDatabase(
                    normalized,
                    scriptPreprocessor.hasFallbackVariable(executableStatement, sessionVariables)
                );
                if (!useResolution.isValid()) {
                    results.add(failedResult(
                        context,
                        "USE",
                        "INVALID_USE_DATABASE",
                        useResolution.getFailureMessage()
                    ));
                    failed++;
                    continue;
                }
                scriptState.useDatabase(useResolution.getDatabase());
                ignored++;
                if (request.getOptions().isIncludeIgnoredStatements()) {
                    results.add(ignoredResult(context, "USE", "USE_DATABASE"));
                }
                continue;
            }
            if (skipReason != null) {
                if ("PURE_QUERY".equals(skipReason)) {
                    registerStandaloneCtes(context);
                }
                if (request.getOptions().isParseJoinConditions()
                    && "PURE_QUERY".equals(skipReason)
                    && joinAnalyzer.hasJoinConditionCandidate(normalized)) {
                    try {
                        StatementLineageResult joinResult = joinAnalyzer.joinConditionOnlyResult(context);
                        validateJoinPhysicalObjects(context);
                        results.add(joinResult);
                    } catch (DatabaseResolutionException ex) {
                        results.add(failedResult(
                            context,
                            "SELECT",
                            "DATABASE_NOT_RESOLVED",
                            ex.getMessage()
                        ));
                        failed++;
                    }
                    continue;
                }
                ignored++;
                if (request.getOptions().isIncludeIgnoredStatements()) {
                    results.add(ignoredResult(context, ignoredStatementType(skipReason), skipReason));
                }
                continue;
            }
            StatementLineageResult result;
            try {
                result = analyzeStatement(context);
            } catch (DatabaseResolutionException ex) {
                result = failedResult(context, "DATABASE_NOT_RESOLVED", ex.getMessage());
            }
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

        return LineageAnalyzeResult.builder()
            .workflowId(request.getWorkflowId())
            .taskId(request.getTaskId())
            .taskVersion(request.getTaskVersion())
            .statements(results)
            .totalStatementCount(statements.size())
            .ignoredStatementCount(ignored)
            .failedStatementCount(failed)
            .build();
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
            logicalTableNames(context),
            context.getTemporaryTableSources().keySet(),
            isCreateTemporaryTable(sql),
            HiveTemporaryTableNamer.temporaryDatabase(context.getRequest())
        );
    }

    private Set<String> logicalTableNames(StatementAnalysisContext context) {
        Set<String> names = new LinkedHashSet<>();
        names.addAll(context.getTemporaryTableSources().keySet());
        names.addAll(context.getScriptCteSources().keySet());
        return names;
    }

    private void registerStandaloneCtes(StatementAnalysisContext context) {
        if (!context.getNormalizedStatement().trim().toLowerCase(Locale.ROOT).startsWith("with ")) {
            return;
        }
        CteExtraction cteExtraction = projectionResolver.extractCtes(context.getNormalizedStatement());
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            try {
                registerStandaloneCte(context, cteBlock);
            } catch (RuntimeException ignored) {
                // A pure query should stay ignorable even when CTE bookkeeping cannot be derived.
            }
        }
    }

    private void registerStandaloneCte(StatementAnalysisContext context, CteBlock cteBlock) {
        ParsedHiveStatement parsed = parseAndBind(context, cteBlock.getSql());
        if (parsed.getInputObjects().isEmpty()) {
            return;
        }
        ColumnResolutionContext columnContext = new ColumnResolutionContext(
            context.getRequest(),
            context.getRequest().getOptions().getParseMode(),
            context.getWarnings(),
            context.getStatementIndex()
        );
        SetOperationExpansionResult expansion = new HiveSetOperationStarExpander(targetColumnResolver)
            .expand(columnContext, cteBlock.getSql());
        Map<String, Map<String, ColumnLineageProjection>> projectionsByTable =
            projectionResolver.extractStatementProjectionTables(expansion.getSql());
        Map<String, ColumnLineageProjection> projections =
            projectionResolver.extractProjectionTable(expansion.getSql(), projectionsByTable);
        projections = projectionResolver.bindPhysicalProjectionSources(
            Collections.singletonMap(cteBlock.getName(), projections),
            parsed.getSourceAliases(),
            databaseBinder
        ).get(cteBlock.getName());
        expansionService.registerScriptCte(
            cteBlock.getName(),
            parsed.getInputObjects(),
            projections,
            context.getScriptCteSources(),
            context.getScriptCteColumns()
        );
    }

    private boolean isLineageStatement(ParsedHiveStatement parsedStatement) {
        return hasLineageShape(parsedStatement)
            && isSupportedLineageType(parsedStatement.getStatementType());
    }

    private boolean hasLineageShape(ParsedHiveStatement parsedStatement) {
        return parsedStatement != null
            && parsedStatement.getOutputObject() != null
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
        return selectStatementAnalyzer.analyze(
            context,
            operation,
            targetTable,
            targetColumns,
            selectItems,
            parsedStatement
        );
    }

    private StatementLineageResult failedResult(
        StatementAnalysisContext context,
        String failureCode,
        String failureMessage
    ) {
        return failedResult(context, "UNKNOWN", failureCode, failureMessage);
    }

    private StatementLineageResult failedResult(
        StatementAnalysisContext context,
        String statementType,
        String failureCode,
        String failureMessage
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        int index = context.getStatementIndex();
        return statementBuilder(request, index)
            .statementType(statementType)
            .rawSql(context.getRawStatement())
            .normalizedSql(context.getNormalizedStatement())
            .parseStatusCode(PARSE_STATUS_FAILED)
            .formalLineageProduced(false)
            .inputObjects(Collections.<DataObjectRef>emptyList())
            .parseMode(request.getOptions().getParseMode())
            .failureCode(failureCode)
            .failureMessage(failureMessage)
            .tableLineages(Collections.<TableLineage>emptyList())
            .columnLineages(Collections.<ColumnLineage>emptyList())
            .warnings(context.getWarnings())
            .build();
    }

    private void validateJoinPhysicalObjects(StatementAnalysisContext context) {
        List<FallbackJoinSource> sources = fallbackJoinSourceResolver.resolve(
            context.getNormalizedStatement(),
            context.getTemporaryTableSources().keySet()
        );
        for (FallbackJoinSource source : sources) {
            if (source.isPhysical()) {
                nameResolver.qualifyTable(
                    source.getQualifiedName(),
                    context.getStatementDatabase()
                );
            }
        }
    }

    private StatementLineageResult ignoredResult(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int index,
        String statementType,
        String skipReason
    ) {
        return statementBuilder(request, index)
            .statementType(statementType)
            .rawSql(rawStatement)
            .normalizedSql(normalizedStatement)
            .parseStatusCode(PARSE_STATUS_IGNORED)
            .formalLineageProduced(false)
            .inputObjects(Collections.<DataObjectRef>emptyList())
            .parseMode(request.getOptions().getParseMode())
            .skipReason(skipReason)
            .tableLineages(Collections.<TableLineage>emptyList())
            .columnLineages(Collections.<ColumnLineage>emptyList())
            .warnings(Collections.<LineageWarning>emptyList())
            .build();
    }

    private StatementLineageResult ignoredResult(
        StatementAnalysisContext context,
        String statementType,
        String skipReason
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        int index = context.getStatementIndex();
        return statementBuilder(request, index)
            .statementType(statementType)
            .rawSql(context.getRawStatement())
            .normalizedSql(context.getNormalizedStatement())
            .parseStatusCode(PARSE_STATUS_IGNORED)
            .formalLineageProduced(false)
            .inputObjects(Collections.<DataObjectRef>emptyList())
            .parseMode(request.getOptions().getParseMode())
            .skipReason(skipReason)
            .tableLineages(Collections.<TableLineage>emptyList())
            .columnLineages(Collections.<ColumnLineage>emptyList())
            .warnings(context.getWarnings())
            .build();
    }

    private StatementLineageResult.Builder statementBuilder(LineageAnalyzeRequest request, int index) {
        return StatementLineageResult.builder()
            .workflowId(request.getWorkflowId())
            .taskId(request.getTaskId())
            .taskVersion(request.getTaskVersion())
            .statementIndex(index)
            .statementId(statementId(request, index));
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
        if (lower.startsWith("create table ") && !AS_WITH_SELECT.matcher(lower).find()) {
            return "CREATE_TABLE_DDL";
        }
        if (INSERT_OVERWRITE_DIRECTORY.matcher(lower).matches()) {
            return "NO_OUTPUT_TABLE";
        }
        if (INSERT_VALUES.matcher(lower).matches()) {
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
