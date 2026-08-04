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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HiveJoinConditionAnalyzer {
    private static final int PARSE_STATUS_SUCCESS = StatementParseStatus.SUCCESS.getCode();
    private static final Pattern QUALIFIED_COLUMN = Pattern.compile("\\b([a-zA-Z_][\\w]*)\\.([a-zA-Z_][\\w]*)\\b");
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
    List<JoinCondition> extractJoinConditions(
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
    void extractJoinConditionsFromQueryBlock(
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
        Map<String, String> sourceAliases = targetColumnResolver.extractTopLevelSourceAliases(queryBlockSql);
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
    List<JoinClause> collectTopLevelJoinClauses(String sql) {
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
            if (depth == 0 && HiveSqlTextUtils.matchesKeywordAt(sql, i, "join")) {
                int onIndex = HiveSqlTextUtils.topLevelKeywordIndex(sql, "on", i + 4);
                if (onIndex < 0) {
                    continue;
                }
                int expressionStart = HiveSqlTextUtils.skipWhitespace(sql, onIndex + 2);
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
            if (depth == 0 && (
                HiveSqlTextUtils.matchesKeywordAt(sql, i, "join")
                    || HiveSqlTextUtils.matchesKeywordAt(sql, i, "where")
                    || HiveSqlTextUtils.matchesKeywordAt(sql, i, "group")
                    || HiveSqlTextUtils.matchesKeywordAt(sql, i, "having")
                    || HiveSqlTextUtils.matchesKeywordAt(sql, i, "order")
                    || HiveSqlTextUtils.matchesKeywordAt(sql, i, "limit")
                    || HiveSqlTextUtils.matchesKeywordAt(sql, i, "union"))) {
                return i;
            }
        }
        return sql.length();
    }
    List<ColumnRef> extractDirectJoinColumns(String expression) {
        List<ColumnRef> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = QUALIFIED_COLUMN.matcher(HiveSqlTextUtils.removeStringLiterals(expression));
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
            String owner = HiveSqlTextUtils.tableNameOfColumn(directColumn.getQualifiedName());
            String columnName = owner == null ? directColumn.getQualifiedName() : HiveSqlTextUtils.columnNameOf(directColumn.getQualifiedName());
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
                    expansionService.addColumn(resolvedColumns, seen, expandedColumn);
                }
            }
        }
        return resolvedColumns;
    }
    String resolveSourceObject(String owner, Map<String, String> sourceAliases) {
        String sourceObject = sourceAliases.get(owner.toLowerCase(Locale.ROOT));
        return sourceObject == null ? owner : sourceObject;
    }
    List<String> resolveUnqualifiedJoinColumnOwners(
        LineageAnalyzeRequest request,
        String columnName,
        List<DataObjectRef> inputObjects,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        if (inputObjects.isEmpty()) {
            targetColumnResolver.addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to resolve unqualified join column " + columnName, statementIndex);
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
        LineageAnalyzeRequest request,
        String sourceObject,
        String columnName,
        Map<String, Map<String, List<ColumnRef>>> cteColumnProjections,
        Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns,
        Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        ColumnLineageProjection temporaryProjection = projectionResolver.columnProjection(sourceObject, columnName, temporaryTableColumns);
        if (temporaryProjection != null && !temporaryProjection.getSourceColumns().isEmpty()) {
            return temporaryProjection.getSourceColumns();
        }
        if (containsProjectionTable(sourceObject, temporaryTableColumns)) {
            targetColumnResolver.addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to map temporary table join column " + sourceObject + "." + columnName, statementIndex);
            return Collections.emptyList();
        }

        Map<String, List<ColumnRef>> cteProjection = cteColumnProjections.get(sourceObject.toLowerCase(Locale.ROOT));
        if (cteProjection != null) {
            List<ColumnRef> projectedColumns = cteProjection.get(columnName.toLowerCase(Locale.ROOT));
            if (projectedColumns != null && !projectedColumns.isEmpty()) {
                return projectedColumns;
            }
            targetColumnResolver.addWarning(warnings, WarningCode.UNKNOWN_COLUMN_LINEAGE, "Unable to map CTE join column " + sourceObject + "." + columnName, statementIndex);
            return Collections.emptyList();
        }

        ColumnLineageProjection viewProjection = expansionService.viewColumnProjection(
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
    Map<String, List<ColumnRef>> extractCteColumnProjections(
        String cteSql,
        Map<String, Map<String, List<ColumnRef>>> existingCteColumnProjections
    ) {
        int selectIndex = HiveSqlTextUtils.topLevelKeywordIndex(cteSql, "select", 0);
        int fromIndex = HiveSqlTextUtils.topLevelKeywordIndex(cteSql, "from", selectIndex < 0 ? 0 : selectIndex);
        if (selectIndex < 0 || fromIndex < 0 || fromIndex <= selectIndex) {
            return Collections.emptyMap();
        }
        String selectList = cteSql.substring(selectIndex + 6, fromIndex);
        Map<String, String> sourceAliases = targetColumnResolver.extractTopLevelSourceAliases(cteSql);
        String defaultSourceTable = firstAliasTarget(sourceAliases);
        Map<String, List<ColumnRef>> projections = new HashMap<>();
        for (String selectItem : HiveSqlTextUtils.splitSelectItems(selectList)) {
            String outputColumn = HiveSqlTextUtils.extractAlias(selectItem);
            String expression = HiveSqlTextUtils.stripAlias(selectItem);
            if (outputColumn == null && projectionResolver.isDirectExpression(expression)) {
                outputColumn = HiveSqlTextUtils.columnNameOf(expression);
                if (outputColumn == null) {
                    outputColumn = expression;
                }
            }
            if (outputColumn == null) {
                continue;
            }
            List<ColumnRef> sourceColumns = projectionResolver.extractSourceColumns(
                expression,
                defaultSourceTable,
                sourceAliases,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap()
            );
            List<ColumnRef> expanded = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (ColumnRef sourceColumn : sourceColumns) {
                String sourceTable = HiveSqlTextUtils.tableNameOfColumn(sourceColumn.getQualifiedName());
                String sourceColumnName = HiveSqlTextUtils.columnNameOf(sourceColumn.getQualifiedName());
                List<ColumnRef> nested = sourceTable == null || sourceColumnName == null
                    ? Collections.singletonList(sourceColumn)
                    : expandCteProjectionColumn(sourceTable, sourceColumnName, existingCteColumnProjections);
                for (ColumnRef nestedColumn : nested) {
                    expansionService.addColumn(expanded, seen, nestedColumn);
                }
            }
            projections.put(outputColumn.toLowerCase(Locale.ROOT), expanded);
        }
        return projections;
    }
    List<ColumnRef> expandCteProjectionColumn(
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
    String firstAliasTarget(Map<String, String> sourceAliases) {
        for (Map.Entry<String, String> alias : sourceAliases.entrySet()) {
            if (alias.getKey().equals(alias.getValue()) || alias.getValue().contains(".")) {
                return alias.getValue();
            }
        }
        return "";
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
