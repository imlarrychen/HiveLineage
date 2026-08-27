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
    private final HiveAstParserAdapter parserAdapter;
    private final HiveTargetColumnResolver targetColumnResolver;
    private final HiveProjectionLineageResolver projectionResolver;
    private final HiveLineageExpansionService expansionService;

    HiveJoinConditionAnalyzer(
        HiveAstParserAdapter parserAdapter,
        HiveTargetColumnResolver targetColumnResolver,
        HiveProjectionLineageResolver projectionResolver,
        HiveLineageExpansionService expansionService
    ) {
        this.parserAdapter = parserAdapter;
        this.targetColumnResolver = targetColumnResolver;
        this.projectionResolver = projectionResolver;
        this.expansionService = expansionService;
    }

    List<JoinCondition> buildJoinConditions(
        StatementAnalysisContext context,
        ParsedHiveStatement parsedStatement
    ) {
        if (parsedStatement == null || parsedStatement.getJoinConditions().isEmpty()) {
            return extractJoinConditions(context);
        }
        String normalizedStatement = context.getNormalizedStatement();
        Map<String, Map<String, ColumnLineageProjection>> projectionTables =
            projectionResolver.extractStatementProjectionTables(normalizedStatement);

        List<JoinCondition> conditions = new ArrayList<>();
        for (ParsedJoinCondition parsedJoinCondition : parsedStatement.getJoinConditions()) {
            List<ColumnRef> directColumns = toColumnRefs(parsedJoinCondition.getDirectJoinColumns());
            List<DataObjectRef> directObjects = toDataObjectRefs(parsedJoinCondition.getDirectJoinObjects());
            Map<String, String> sourceAliases = sourceAliasesFromDirectJoin(
                parsedJoinCondition.getDirectJoinColumns(),
                parsedJoinCondition.getDirectJoinObjects()
            );
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
            aliases.put(simpleName.toLowerCase(Locale.ROOT), object);
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
        ParsedHiveStatement parsedStatement = parseStatement(normalizedStatement);
        List<JoinCondition> joinConditions = buildJoinConditions(context, parsedStatement);
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
            context.getWarnings()
        );
    }
    boolean hasJoinConditionCandidate(String statement) {
        return Pattern.compile("(?is)\\bjoin\\b.+\\bon\\b").matcher(statement).find()
            || Pattern.compile("(?is)\\bfrom\\b[^;]+,[^;]+\\bwhere\\b").matcher(statement).find();
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
        Map<String, String> sourceAliases = targetColumnResolver.extractTopLevelSourceAliases(queryBlockSql);
        List<JoinClause> clauses = collectTopLevelJoinClauses(queryBlockSql);
        for (JoinClause clause : clauses) {
            List<ColumnRef> directColumns = extractDirectJoinColumns(clause.getExpression());
            List<DataObjectRef> directObjects = directJoinObjects(directColumns, sourceAliases);
            List<ColumnRef> joinColumns = resolveJoinColumns(
                context,
                directColumns,
                sourceAliases,
                inputObjectsFromSourceAliases(sourceAliases),
                projectionTables
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
    List<DataObjectRef> directJoinObjects(List<ColumnRef> directColumns, Map<String, String> sourceAliases) {
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
            String columnName = owner == null ? directColumn.getQualifiedName() : HiveSqlTextUtils.columnNameOf(directColumn.getQualifiedName());
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
    private String resolveQualifiedJoinOwner(
        StatementAnalysisContext context,
        String owner,
        QualifiedOwnerResolver ownerResolver,
        Map<String, Map<String, ColumnLineageProjection>> projectionTables
    ) {
        String normalizedOwner = owner.toLowerCase(Locale.ROOT);
        if (projectionTables.containsKey(normalizedOwner)
            || containsProjectionTable(owner, context.getTemporaryTableColumns())) {
            return owner;
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
        if (projectionsByTable.containsKey(tableName)) {
            return true;
        }
        return tableName.contains(".")
            && projectionsByTable.containsKey(tableName.substring(tableName.lastIndexOf('.') + 1));
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
            expansionService.addInputObject(tables, seen, directObject);
        }
        return tables;
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
    private ParsedHiveStatement parseStatement(String normalizedStatement) {
        try {
            return parserAdapter.parse(normalizedStatement);
        } catch (RuntimeException ex) {
            return new ParsedHiveStatement(StatementType.UNSUPPORTED, null, Collections.<DataObjectRef>emptyList(), normalizedStatement);
        }
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
