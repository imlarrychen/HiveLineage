package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HiveJoinConditionAnalyzer {
    private static final int PARSE_STATUS_SUCCESS = StatementParseStatus.SUCCESS.getCode();
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile(
        "(?<![\\w.])([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*){1,2})(?![\\w.])"
    );
    private static final Pattern JOIN_ON_CANDIDATE = Pattern.compile("(?is)\\bjoin\\b.+\\bon\\b");
    private static final Pattern COMMA_JOIN_WHERE_CANDIDATE = Pattern.compile(
        "(?is)\\bfrom\\b[^;]+,[^;]+\\bwhere\\b"
    );
    private static final Pattern LEFT_SEMI_JOIN_PREFIX = Pattern.compile("(?is).*\\bleft\\s+semi\\s+$");
    private static final Pattern LEFT_ANTI_JOIN_PREFIX = Pattern.compile("(?is).*\\bleft\\s+anti\\s+$");
    private static final Pattern LEFT_JOIN_PREFIX = Pattern.compile("(?is).*\\bleft\\s+(outer\\s+)?$");
    private static final Pattern RIGHT_JOIN_PREFIX = Pattern.compile("(?is).*\\bright\\s+(outer\\s+)?$");
    private static final Pattern FULL_JOIN_PREFIX = Pattern.compile("(?is).*\\bfull\\s+(outer\\s+)?$");
    private static final Pattern CROSS_JOIN_PREFIX = Pattern.compile("(?is).*\\bcross\\s+$");
    private static final String DOT_REGEX = "\\.";
    private final HiveAstParserAdapter parserAdapter;
    private final HiveTargetColumnResolver targetColumnResolver;
    private final HiveProjectionLineageResolver projectionResolver;
    private final HiveLineageExpansionService expansionService;
    private final HiveStatementDatabaseBinder databaseBinder;
    private final HiveJoinQueryBlockScopeResolver queryBlockScopeResolver =
        new HiveJoinQueryBlockScopeResolver();

    HiveJoinConditionAnalyzer(
        HiveAstParserAdapter parserAdapter,
        HiveTargetColumnResolver targetColumnResolver,
        HiveProjectionLineageResolver projectionResolver,
        HiveLineageExpansionService expansionService,
        HiveStatementDatabaseBinder databaseBinder
    ) {
        this.parserAdapter = parserAdapter;
        this.targetColumnResolver = targetColumnResolver;
        this.projectionResolver = projectionResolver;
        this.expansionService = expansionService;
        this.databaseBinder = databaseBinder;
    }

    List<JoinCondition> buildJoinConditions(
        StatementAnalysisContext context,
        ParsedHiveStatement parsedStatement
    ) {
        if (parsedStatement == null || parsedStatement.getJoinConditions().isEmpty()) {
            return extractJoinConditions(context);
        }
        String normalizedStatement = context.getNormalizedStatement();
        Map<String, Map<String, ColumnLineageProjection>> rawProjectionTables =
            projectionResolver.extractStatementProjectionTables(normalizedStatement);
        HiveJoinQueryBlockScopeResolver.QueryBlockScopes queryBlockScopes =
            queryBlockScopeResolver.resolve(normalizedStatement);
        Map<String, Map<String, String>> aliasesByQueryBlock = new HashMap<>();
        Map<String, Set<String>> ambiguousAliasesByQueryBlock = new HashMap<>();
        for (Map.Entry<String, String> queryBlock : queryBlockScopes.getSqlByPath().entrySet()) {
            HiveTargetColumnResolver.SourceAliasScope aliasScope =
                targetColumnResolver.extractTopLevelSourceAliasScope(queryBlock.getValue());
            aliasesByQueryBlock.put(
                queryBlock.getKey(),
                bindQueryBlockSourceAliases(
                    context,
                    aliasScope.getAliases(),
                    rawProjectionTables
                )
            );
            ambiguousAliasesByQueryBlock.put(
                queryBlock.getKey(),
                aliasScope.getAmbiguousSimpleNames()
            );
        }
        Map<String, String> projectionSourceAliases = databaseBinder.bindExtractedAliasTargets(
            targetColumnResolver.extractSourceAliases(normalizedStatement),
            parsedStatement.getInputObjects()
        );
        projectionSourceAliases.putAll(parsedStatement.getSourceAliases());
        Map<String, Map<String, String>> aliasesByProjection = new HashMap<>();
        for (Map.Entry<String, String> definition : queryBlockScopes.getProjectionDefinitionPaths().entrySet()) {
            Map<String, String> aliases = aliasesByQueryBlock.get(definition.getValue());
            if (aliases != null) {
                aliasesByProjection.put(definition.getKey(), aliases);
            }
        }
        Map<String, Map<String, ColumnLineageProjection>> projectionTables =
            projectionResolver.bindScopedPhysicalProjectionSources(
                rawProjectionTables,
                aliasesByProjection,
                projectionSourceAliases,
                databaseBinder
            );

        List<JoinCondition> conditions = new ArrayList<>();
        for (ParsedJoinCondition parsedJoinCondition : parsedStatement.getJoinConditions()) {
            Map<String, String> scopedAliases = aliasesByQueryBlock.get(parsedJoinCondition.getQueryBlockPath());
            boolean rootQueryBlock = "root".equals(parsedJoinCondition.getQueryBlockPath());
            Map<String, String> conditionSourceAliases = rootQueryBlock
                ? new HashMap<>(parsedStatement.getSourceAliases())
                : new HashMap<String, String>();
            Set<String> ambiguousAliases = ambiguousAliasesByQueryBlock.get(
                parsedJoinCondition.getQueryBlockPath()
            );
            if (ambiguousAliases != null) {
                for (String ambiguousAlias : ambiguousAliases) {
                    conditionSourceAliases.remove(ambiguousAlias);
                }
            }
            if (scopedAliases != null) {
                conditionSourceAliases.putAll(scopedAliases);
            }
            List<ColumnRef> expressionColumns = extractDirectJoinColumns(parsedJoinCondition.getJoinExpression());
            List<ColumnRef> directColumns = bindDirectJoinColumns(
                context,
                expressionColumns,
                conditionSourceAliases,
                projectionTables
            );
            for (String parsedColumn : parsedJoinCondition.getDirectJoinColumns()) {
                if (HiveSqlTextUtils.tableNameOfColumn(parsedColumn) == null) {
                    addDirectColumnIfAbsent(directColumns, parsedColumn);
                }
            }
            List<DataObjectRef> directObjects = directJoinObjects(
                context,
                expressionColumns,
                conditionSourceAliases,
                projectionTables
            );
            if (directObjects.isEmpty()) {
                directObjects = toDataObjectRefs(parsedJoinCondition.getDirectJoinObjects());
            }
            Map<String, String> sourceAliases = sourceAliasesFromDirectJoin(
                qualifiedNames(directColumns),
                qualifiedObjectNames(directObjects)
            );
            sourceAliases.putAll(conditionSourceAliases);
            List<ColumnRef> joinColumns = resolveJoinColumns(
                context,
                directColumns,
                sourceAliases,
                parsedStatement.getInputObjects(),
                projectionTables
            );
            if (directObjects.isEmpty() && hasUnqualifiedColumns(directColumns)) {
                directObjects = joinTables(joinColumns, directObjects);
            }
            conditions.add(JoinCondition.builder()
                .joinIndex(parsedJoinCondition.getJoinIndex())
                .queryBlockPath(parsedJoinCondition.getQueryBlockPath())
                .joinType(parsedJoinCondition.getJoinType())
                .joinTypeName(parsedJoinCondition.getJoinTypeName())
                .directJoinObjects(directObjects)
                .directJoinColumns(directColumns)
                .joinTables(joinTables(joinColumns, directObjects))
                .joinColumns(joinColumns)
                .joinExpression(parsedJoinCondition.getJoinExpression())
                .build());
        }
        return conditions;
    }
    private List<ColumnRef> bindDirectJoinColumns(
        StatementAnalysisContext context,
        List<ColumnRef> expressionColumns,
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        List<ColumnRef> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef column : expressionColumns) {
            String qualifiedName = column.getQualifiedName();
            String owner = HiveSqlTextUtils.tableNameOfColumn(qualifiedName);
            String columnName = HiveSqlTextUtils.columnNameOf(qualifiedName);
            String sourceObject = owner == null ? null : sourceAliases.get(owner.toLowerCase(Locale.ROOT));
            boolean logical = isLogicalJoinColumnOwner(context, owner, sourceObject, projectionTables);
            String resolved = sourceObject != null && sourceObject.contains(".") && !logical
                ? sourceObject + "." + columnName
                : qualifiedName;
            expansionService.addColumn(columns, seen, new ColumnRef(resolved));
        }
        return columns;
    }
    private void addDirectColumnIfAbsent(List<ColumnRef> columns, String qualifiedName) {
        for (ColumnRef column : columns) {
            if (column.getQualifiedName().equalsIgnoreCase(qualifiedName)) {
                return;
            }
        }
        columns.add(new ColumnRef(qualifiedName));
    }
    private List<String> qualifiedNames(List<ColumnRef> columns) {
        List<String> names = new ArrayList<>();
        for (ColumnRef column : columns) {
            names.add(column.getQualifiedName());
        }
        return names;
    }
    private List<String> qualifiedObjectNames(List<DataObjectRef> objects) {
        List<String> names = new ArrayList<>();
        for (DataObjectRef object : objects) {
            names.add(object.getQualifiedName());
        }
        return names;
    }
    List<ColumnRef> toColumnRefs(List<String> qualifiedNames) {
        List<ColumnRef> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String qualifiedName : qualifiedNames) {
            expansionService.addColumn(columns, seen, new ColumnRef(qualifiedName));
        }
        return columns;
    }
    List<DataObjectRef> toDataObjectRefs(List<String> qualifiedNames) {
        List<DataObjectRef> objects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String qualifiedName : qualifiedNames) {
            expansionService.addInputObject(objects, seen, new DataObjectRef(qualifiedName));
        }
        return objects;
    }
    Map<String, String> sourceAliasesFromDirectJoin(List<String> directColumns, List<String> directObjects) {
        Map<String, String> aliases = new HashMap<>();
        int objectIndex = 0;
        Set<String> seenOwners = new LinkedHashSet<>();
        for (String directColumn : directColumns) {
            String owner = HiveSqlTextUtils.tableNameOfColumn(directColumn);
            if (owner == null || !seenOwners.add(owner.toLowerCase(Locale.ROOT))) {
                continue;
            }
            String object = objectIndex < directObjects.size() ? directObjects.get(objectIndex) : owner;
            objectIndex++;
            aliases.put(owner.toLowerCase(Locale.ROOT), object);
            aliases.put(object.toLowerCase(Locale.ROOT), object);
            String simpleName = object.contains(".") ? object.substring(object.lastIndexOf('.') + 1) : object;
            String normalizedSimpleName = simpleName.toLowerCase(Locale.ROOT);
            if (!aliases.containsKey(normalizedSimpleName)) {
                aliases.put(normalizedSimpleName, object);
            }
        }
        return aliases;
    }
    StatementLineageResult joinConditionOnlyResult(
        StatementAnalysisContext context
    ) {
        LineageAnalyzeRequest request = context.getRequest();
        String rawStatement = context.getRawStatement();
        String normalizedStatement = context.getNormalizedStatement();
        int index = context.getStatementIndex();
        ParsedHiveStatement parsedStatement = parseAndBind(context, normalizedStatement);
        List<JoinCondition> joinConditions = buildJoinConditions(context, parsedStatement);
        return StatementLineageResult.builder()
            .workflowId(request.getWorkflowId())
            .taskId(request.getTaskId())
            .taskVersion(request.getTaskVersion())
            .statementIndex(index)
            .statementId(statementId(request, index))
            .statementType("SELECT")
            .rawSql(rawStatement)
            .normalizedSql(normalizedStatement)
            .parseStatusCode(PARSE_STATUS_SUCCESS)
            .formalLineageProduced(false)
            .inputObjects(joinInputObjects(joinConditions))
            .parseMode(request.getOptions().getParseMode())
            .tableLineages(Collections.<TableLineage>emptyList())
            .columnLineages(Collections.<ColumnLineage>emptyList())
            .joinConditions(joinConditions)
            .warnings(context.getWarnings())
            .build();
    }
    boolean hasJoinConditionCandidate(String statement) {
        return JOIN_ON_CANDIDATE.matcher(statement).find()
            || COMMA_JOIN_WHERE_CANDIDATE.matcher(statement).find();
    }
    List<DataObjectRef> joinInputObjects(List<JoinCondition> joinConditions) {
        List<DataObjectRef> inputObjects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JoinCondition joinCondition : joinConditions) {
            for (DataObjectRef joinTable : joinCondition.getJoinTables()) {
                expansionService.addInputObject(inputObjects, seen, joinTable);
            }
        }
        if (!inputObjects.isEmpty()) {
            return inputObjects;
        }
        for (JoinCondition joinCondition : joinConditions) {
            for (DataObjectRef directObject : joinCondition.getDirectJoinObjects()) {
                expansionService.addInputObject(inputObjects, seen, directObject);
            }
        }
        return inputObjects;
    }
    List<JoinCondition> extractJoinConditions(StatementAnalysisContext context) {
        String normalizedStatement = context.getNormalizedStatement();
        if (!hasJoinConditionCandidate(normalizedStatement)) {
            return Collections.emptyList();
        }
        CteExtraction cteExtraction = extractCtes(normalizedStatement);
        Map<String, Map<String, ColumnLineageProjection>> projectionTables =
            projectionResolver.extractStatementProjectionTables(normalizedStatement);

        List<JoinCondition> conditions = new ArrayList<>();
        JoinIndexCounter counter = new JoinIndexCounter();
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            extractJoinConditionsFromQueryBlock(
                context,
                cteBlock.getSql(),
                "cte." + cteBlock.getName(),
                counter,
                projectionTables,
                conditions
            );
        }
        extractJoinConditionsFromQueryBlock(
            context,
            cteExtraction.getRootSql(),
            "root",
            counter,
            projectionTables,
            conditions
        );
        return conditions;
    }
    void extractJoinConditionsFromQueryBlock(
        StatementAnalysisContext context,
        String queryBlockSql,
        String queryBlockPath,
        JoinIndexCounter counter,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables,
        List<JoinCondition> conditions
    ) {
        Map<String, String> sourceAliases = bindQueryBlockSourceAliases(
            context,
            targetColumnResolver.extractTopLevelSourceAliases(queryBlockSql),
            projectionTables
        );
        List<JoinClause> clauses = collectTopLevelJoinClauses(queryBlockSql);
        for (JoinClause clause : clauses) {
            List<ColumnRef> expressionColumns = extractDirectJoinColumns(clause.getExpression());
            List<ColumnRef> directColumns = bindDirectJoinColumns(
                context,
                expressionColumns,
                sourceAliases,
                projectionTables
            );
            List<DataObjectRef> directObjects = directJoinObjects(
                context,
                expressionColumns,
                sourceAliases,
                projectionTables
            );
            List<ColumnRef> joinColumns = resolveJoinColumns(
                context,
                directColumns,
                sourceAliases,
                inputObjectsFromSourceAliases(sourceAliases),
                projectionTables
            );
            List<DataObjectRef> joinTables = joinTables(joinColumns, directObjects);
            JoinTypeInfo joinTypeInfo = resolveJoinType(queryBlockSql, clause.getJoinKeywordIndex());
            conditions.add(JoinCondition.builder()
                .joinIndex(counter.next())
                .queryBlockPath(queryBlockPath)
                .joinType(joinTypeInfo.getCode())
                .joinTypeName(joinTypeInfo.getName())
                .directJoinObjects(directObjects)
                .directJoinColumns(directColumns)
                .joinTables(joinTables)
                .joinColumns(joinColumns)
                .joinExpression(clause.getExpression())
                .build());
        }
    }
    private Map<String, String> bindQueryBlockSourceAliases(
        StatementAnalysisContext context,
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        List<DataObjectRef> rawInputs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String sourceObject : sourceAliases.values()) {
            if (seen.add(sourceObject.toLowerCase(Locale.ROOT))) {
                rawInputs.add(new DataObjectRef(sourceObject));
            }
        }
        if (rawInputs.isEmpty()) {
            return sourceAliases;
        }
        Set<String> temporaryNames = context.getTemporaryTableSources().keySet();
        Set<String> logicalNames = new LinkedHashSet<>(temporaryNames);
        for (String sourceObject : sourceAliases.values()) {
            if (containsProjectionTable(sourceObject, projectionTables)) {
                logicalNames.add(sourceObject);
            }
        }
        ParsedHiveStatement synthetic = new ParsedHiveStatement(
            StatementType.UNSUPPORTED,
            null,
            rawInputs,
            context.getNormalizedStatement()
        );
        ParsedHiveStatement bound = databaseBinder.bind(synthetic, context.getStatementDatabase(), logicalNames,
            temporaryNames, false, HiveTemporaryTableNamer.temporaryDatabase(context.getRequest()));
        return databaseBinder.bindExtractedAliasTargets(sourceAliases, bound.getInputObjects());
    }
    List<JoinClause> collectTopLevelJoinClauses(String sql) {
        List<JoinClause> clauses = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int position = 0;
        while (position < sql.length()) {
            char ch = sql.charAt(position);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                position++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                position++;
                continue;
            }
            if (ch == '(') {
                depth++;
                position++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                position++;
                continue;
            }
            if (depth == 0 && HiveSqlTextUtils.matchesKeywordAt(sql, position, "join")) {
                int onIndex = HiveSqlTextUtils.topLevelKeywordIndex(sql, "on", position + 4);
                if (onIndex < 0) {
                    position++;
                    continue;
                }
                int expressionStart = HiveSqlTextUtils.skipWhitespace(sql, onIndex + 2);
                int expressionEnd = findJoinExpressionEnd(sql, expressionStart);
                String expression = sql.substring(expressionStart, expressionEnd).trim();
                if (!expression.isEmpty()) {
                    clauses.add(new JoinClause(position, expression));
                }
                position = Math.max(position + 1, expressionEnd);
                continue;
            }
            position++;
        }
        return clauses;
    }
    int findJoinExpressionEnd(String sql, int start) {
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
            if (depth == 0 && isJoinExpressionBoundary(sql, i)) {
                return i;
            }
        }
        return sql.length();
    }

    private boolean isJoinExpressionBoundary(String sql, int position) {
        return isPrimaryJoinBoundary(sql, position) || isSecondaryJoinBoundary(sql, position);
    }

    private boolean isPrimaryJoinBoundary(String sql, int position) {
        return HiveSqlTextUtils.matchesKeywordAt(sql, position, "join")
            || HiveSqlTextUtils.matchesKeywordAt(sql, position, "where")
            || HiveSqlTextUtils.matchesKeywordAt(sql, position, "group")
            || HiveSqlTextUtils.matchesKeywordAt(sql, position, "having");
    }

    private boolean isSecondaryJoinBoundary(String sql, int position) {
        return HiveSqlTextUtils.matchesKeywordAt(sql, position, "order")
            || HiveSqlTextUtils.matchesKeywordAt(sql, position, "limit")
            || HiveSqlTextUtils.matchesKeywordAt(sql, position, "union");
    }
    List<ColumnRef> extractDirectJoinColumns(String expression) {
        List<ColumnRef> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = QUALIFIED_COLUMN.matcher(HiveSqlTextUtils.removeStringLiterals(expression));
        while (matcher.find()) {
            String qualifiedName = matcher.group(1);
            if (seen.add(qualifiedName.toLowerCase(Locale.ROOT))) {
                columns.add(new ColumnRef(qualifiedName));
            }
        }
        return columns;
    }
    List<DataObjectRef> directJoinObjects(
        StatementAnalysisContext context,
        List<ColumnRef> directColumns,
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        List<DataObjectRef> objects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef directColumn : directColumns) {
            String tableName = HiveSqlTextUtils.tableNameOfColumn(directColumn.getQualifiedName());
            if (tableName == null) {
                continue;
            }
            String resolved = sourceAliases.get(tableName.toLowerCase(Locale.ROOT));
            if (resolved == null) {
                resolved = tableName;
            } else if (containsProjectionTable(resolved, projectionTables)) {
                resolved = tableName;
            }
            expansionService.addInputObject(objects, seen, new DataObjectRef(resolved));
        }
        return objects;
    }
    List<ColumnRef> resolveJoinColumns(
        StatementAnalysisContext context,
        List<ColumnRef> directColumns,
        Map<String, String> sourceAliases,
        List<DataObjectRef> inputObjects,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        List<ColumnRef> resolvedColumns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        QualifiedOwnerResolver ownerResolver = new QualifiedOwnerResolver(inputObjects, sourceAliases);
        for (ColumnRef directColumn : directColumns) {
            String owner = HiveSqlTextUtils.tableNameOfColumn(directColumn.getQualifiedName());
            String columnName = owner == null
                ? directColumn.getQualifiedName()
                : HiveSqlTextUtils.columnNameOf(directColumn.getQualifiedName());
            if (columnName == null) {
                continue;
            }
            List<String> sourceObjects;
            if (owner == null) {
                sourceObjects = resolveUnqualifiedJoinColumnOwners(
                    context.getRequest(),
                    columnName,
                    inputObjects,
                    context.getWarnings(),
                    context.getStatementIndex()
                );
            } else {
                String sourceObject = resolveQualifiedJoinOwner(
                    context,
                    owner,
                    ownerResolver,
                    sourceAliases,
                    projectionTables
                );
                sourceObjects = sourceObject == null
                    ? Collections.<String>emptyList()
                    : Collections.singletonList(sourceObject);
            }
            for (String sourceObject : sourceObjects) {
                List<ColumnRef> expanded = expandJoinColumn(
                    context,
                    sourceObject,
                    columnName,
                    projectionTables
                );
                for (ColumnRef expandedColumn : expanded) {
                    expansionService.addColumn(resolvedColumns, seen, expandedColumn);
                }
            }
        }
        return resolvedColumns;
    }
    private boolean isLogicalJoinColumnOwner(
        StatementAnalysisContext context,
        String owner,
        String sourceObject,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        return owner != null
            && (isProjectionTable(owner, context, projectionTables)
            || isResolvedProjectionTable(sourceObject, context, projectionTables));
    }

    private boolean isResolvedProjectionTable(
        String sourceObject,
        StatementAnalysisContext context,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        return sourceObject != null && isProjectionTable(sourceObject, context, projectionTables);
    }

    private boolean isProjectionTable(
        String tableName,
        StatementAnalysisContext context,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        return containsProjectionTable(tableName, projectionTables)
            || containsProjectionTable(tableName, context.getTemporaryTableColumns());
    }
    private String resolveQualifiedJoinOwner(
        StatementAnalysisContext context,
        String owner,
        QualifiedOwnerResolver ownerResolver,
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        String normalizedOwner = owner.toLowerCase(Locale.ROOT);
        if (projectionTables.containsKey(normalizedOwner)
            || containsProjectionTable(owner, context.getTemporaryTableColumns())) {
            return owner;
        }
        String aliasTarget = sourceAliases.get(normalizedOwner);
        if (aliasTarget != null && (
            containsProjectionTable(aliasTarget, projectionTables)
                || containsProjectionTable(aliasTarget, context.getTemporaryTableColumns())
        )) {
            return aliasTarget;
        }
        String resolved = ownerResolver.resolve(owner);
        if (resolved != null) {
            return resolved;
        }
        targetColumnResolver.addWarning(
            context.getWarnings(),
            WarningCode.UNKNOWN_COLUMN_LINEAGE,
            "Unable to resolve qualified join owner " + owner,
            context.getStatementIndex()
        );
        return null;
    }
    List<String> resolveUnqualifiedJoinColumnOwners(
        LineageAnalyzeRequest request,
        String columnName,
        List<DataObjectRef> inputObjects,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (inputObjects.isEmpty()) {
            targetColumnResolver.addWarning(
                warnings,
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to resolve unqualified join column " + columnName,
                statementIndex
            );
            return Collections.emptyList();
        }
        List<String> owners = new ArrayList<>();
        String normalizedColumn = columnName.toLowerCase(Locale.ROOT);
        for (DataObjectRef inputObject : inputObjects) {
            HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata != null && projectionResolver.containsColumn(metadata, normalizedColumn)) {
                owners.add(inputObject.getQualifiedName());
            }
        }
        if (owners.size() == 1) {
            return owners;
        }
        String message = owners.isEmpty()
            ? "Unable to resolve unqualified join column " + columnName
            : "Ambiguous unqualified join column " + columnName + " in " + HiveSqlTextUtils.joinWithComma(owners);
        targetColumnResolver.addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, message, statementIndex);
        return Collections.emptyList();
    }
    boolean hasUnqualifiedColumns(List<ColumnRef> columns) {
        for (ColumnRef column : columns) {
            if (HiveSqlTextUtils.tableNameOfColumn(column.getQualifiedName()) == null) {
                return true;
            }
        }
        return false;
    }
    List<DataObjectRef> inputObjectsFromSourceAliases(Map<String, String> sourceAliases) {
        List<DataObjectRef> inputObjects = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String sourceObject : sourceAliases.values()) {
            if (sourceObject.contains(".")) {
                expansionService.addInputObject(inputObjects, seen, new DataObjectRef(sourceObject));
            }
        }
        return inputObjects;
    }
    List<ColumnRef> expandJoinColumn(
        StatementAnalysisContext context,
        String sourceObject,
        String columnName,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        ColumnLineageProjection temporaryProjection = projectionResolver.columnProjection(
            sourceObject,
            columnName,
            context.getTemporaryTableColumns()
        );
        if (temporaryProjection != null) {
            if (!temporaryProjection.isUnresolved()) {
                return temporaryProjection.getSourceColumns();
            }
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to map temporary table join column " + sourceObject + "." + columnName,
                context.getStatementIndex()
            );
            return Collections.emptyList();
        }
        if (containsProjectionTable(sourceObject, context.getTemporaryTableColumns())) {
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to map temporary table join column " + sourceObject + "." + columnName,
                context.getStatementIndex()
            );
            return Collections.emptyList();
        }

        ColumnLineageProjection statementProjection = projectionResolver.columnProjection(
            sourceObject,
            columnName,
            projectionTables
        );
        if (statementProjection != null) {
            if (!statementProjection.isUnresolved()) {
                return statementProjection.getSourceColumns();
            }
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to map CTE join column " + sourceObject + "." + columnName,
                context.getStatementIndex()
            );
            return Collections.emptyList();
        }
        if (containsProjectionTable(sourceObject, projectionTables)) {
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to map CTE join column " + sourceObject + "." + columnName,
                context.getStatementIndex()
            );
            return Collections.emptyList();
        }

        ColumnLineageProjection viewProjection = expansionService.viewColumnProjection(
            context,
            sourceObject,
            columnName
        );
        if (viewProjection != null) {
            if (!viewProjection.isUnresolved()) {
                return viewProjection.getSourceColumns();
            }
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to map view join column " + sourceObject + "." + columnName,
                context.getStatementIndex()
            );
            return Collections.emptyList();
        }
        if (expansionService.isKnownView(context.getRequest(), sourceObject)) {
            targetColumnResolver.addWarning(
                context.getWarnings(),
                WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "Unable to map view join column " + sourceObject + "." + columnName,
                context.getStatementIndex()
            );
            return Collections.emptyList();
        }
        return Collections.singletonList(new ColumnRef(sourceObject + "." + columnName));
    }
    boolean containsProjectionTable(
        String tableName,
        Map<String, Map<String, ColumnLineageProjection>> projectionsByTable
    ) {
        return tableName != null && projectionsByTable.containsKey(tableName);
    }
    List<DataObjectRef> joinTables(List<ColumnRef> joinColumns, List<DataObjectRef> directObjects) {
        List<DataObjectRef> tables = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ColumnRef joinColumn : joinColumns) {
            String tableName = HiveSqlTextUtils.tableNameOfColumn(joinColumn.getQualifiedName());
            if (tableName != null) {
                expansionService.addInputObject(tables, seen, new DataObjectRef(tableName));
            }
        }
        if (!tables.isEmpty()) {
            return tables;
        }
        for (DataObjectRef directObject : directObjects) {
            if (isQualifiedPhysicalObject(directObject)) {
                expansionService.addInputObject(tables, seen, directObject);
            }
        }
        return tables;
    }
    private boolean isQualifiedPhysicalObject(DataObjectRef object) {
        if (object == null || object.getQualifiedName() == null) {
            return false;
        }
        String[] parts = object.getQualifiedName().split(DOT_REGEX, -1);
        return parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty();
    }
    CteExtraction extractCtes(String sql) {
        String trimmed = sql.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("with ")) {
            return new CteExtraction(Collections.<CteBlock>emptyList(), trimmed);
        }
        List<CteBlock> cteBlocks = new ArrayList<>();
        int position = HiveSqlTextUtils.skipWhitespace(trimmed, 4);
        while (position < trimmed.length()) {
            int nameStart = position;
            int nameEnd = HiveSqlTextUtils.readIdentifier(trimmed, nameStart);
            if (nameEnd <= nameStart) {
                break;
            }
            String cteName = HiveSqlTextUtils.normalizeIdentifier(trimmed.substring(nameStart, nameEnd));
            position = HiveSqlTextUtils.skipWhitespace(trimmed, nameEnd);
            if (position < trimmed.length() && trimmed.charAt(position) == '(') {
                position = HiveSqlTextUtils.findMatchingParen(trimmed, position) + 1;
                position = HiveSqlTextUtils.skipWhitespace(trimmed, position);
            }
            int asStart = position;
            int asEnd = HiveSqlTextUtils.readIdentifier(trimmed, asStart);
            if (asEnd <= asStart || !"as".equalsIgnoreCase(trimmed.substring(asStart, asEnd))) {
                break;
            }
            position = HiveSqlTextUtils.skipWhitespace(trimmed, asEnd);
            if (position >= trimmed.length() || trimmed.charAt(position) != '(') {
                break;
            }
            int bodyStart = position + 1;
            int bodyEnd = HiveSqlTextUtils.findMatchingParen(trimmed, position);
            if (bodyEnd < bodyStart) {
                break;
            }
            cteBlocks.add(new CteBlock(cteName, trimmed.substring(bodyStart, bodyEnd).trim()));
            position = HiveSqlTextUtils.skipWhitespace(trimmed, bodyEnd + 1);
            if (position < trimmed.length() && trimmed.charAt(position) == ',') {
                position = HiveSqlTextUtils.skipWhitespace(trimmed, position + 1);
                continue;
            }
            break;
        }
        String rootSql = position < trimmed.length() ? trimmed.substring(position).trim() : "";
        return new CteExtraction(cteBlocks, rootSql);
    }

    String rootSqlAfterCtes(String sql) {
        return extractCtes(sql).getRootSql();
    }

    JoinTypeInfo resolveJoinType(String sql, int joinKeywordIndex) {
        String prefix = sql.substring(Math.max(0, joinKeywordIndex - 32), joinKeywordIndex).toLowerCase(Locale.ROOT);
        if (LEFT_SEMI_JOIN_PREFIX.matcher(prefix).matches()) {
            return new JoinTypeInfo(5, "LEFT_SEMI_JOIN");
        }
        if (LEFT_ANTI_JOIN_PREFIX.matcher(prefix).matches()) {
            return new JoinTypeInfo(6, "LEFT_ANTI_JOIN");
        }
        if (LEFT_JOIN_PREFIX.matcher(prefix).matches()) {
            return new JoinTypeInfo(2, "LEFT_JOIN");
        }
        if (RIGHT_JOIN_PREFIX.matcher(prefix).matches()) {
            return new JoinTypeInfo(3, "RIGHT_JOIN");
        }
        if (FULL_JOIN_PREFIX.matcher(prefix).matches()) {
            return new JoinTypeInfo(4, "FULL_JOIN");
        }
        if (CROSS_JOIN_PREFIX.matcher(prefix).matches()) {
            return new JoinTypeInfo(7, "CROSS_JOIN");
        }
        return new JoinTypeInfo(1, "INNER_JOIN");
    }
    private ParsedHiveStatement parseStatement(String normalizedStatement) {
        try {
            return parserAdapter.parse(normalizedStatement);
        } catch (RuntimeException ex) {
            return new ParsedHiveStatement(StatementType.UNSUPPORTED, null, Collections.<DataObjectRef>emptyList(), normalizedStatement);
        }
    }
    private ParsedHiveStatement parseAndBind(StatementAnalysisContext context, String normalizedStatement) {
        Set<String> temporaryNames = context.getTemporaryTableSources().keySet();
        return databaseBinder.bind(parseStatement(normalizedStatement), context.getStatementDatabase(),
            temporaryNames, temporaryNames, false, HiveTemporaryTableNamer.temporaryDatabase(context.getRequest()));
    }
    private String statementId(LineageAnalyzeRequest request, int index) {
        return request.getWorkflowId() + "_" + request.getTaskId() + "_" + request.getTaskVersion() + "_" + index;
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
}
