package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import java.util.ArrayList;
import java.util.Arrays;
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

final class HiveProjectionLineageResolver {
    private static final Pattern IDENTIFIER = Pattern.compile(
        "(?<![\\w.])([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*){0,2})(?![\\w.])"
    );
    private static final Pattern DOTTED_IDENTIFIER = Pattern.compile(
        "(?<![\\w.])[a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)+(?![\\w.])"
    );
    private final HiveAstParserAdapter parserAdapter;
    private final HiveTargetColumnResolver targetColumnResolver;

    HiveProjectionLineageResolver(HiveAstParserAdapter parserAdapter, HiveTargetColumnResolver targetColumnResolver) {
        this.parserAdapter = parserAdapter;
        this.targetColumnResolver = targetColumnResolver;
    }

    List<ColumnRef> setOperationSourceColumns(ParsedHiveStatement parsedStatement, int selectIndex) {
        List<String> sourceColumns = parsedStatement.getSetOperationSourceColumnsBySelectIndex().get(selectIndex);
        if (sourceColumns == null || sourceColumns.isEmpty()) {
            return Collections.emptyList();
        }
        List<ColumnRef> refs = new ArrayList<>();
        for (String sourceColumn : sourceColumns) {
            QualifiedColumnReference reference = QualifiedColumnReference.parse(sourceColumn);
            if (reference == null || !reference.isStar()) {
                refs.add(new ColumnRef(sourceColumn));
            }
        }
        return refs;
    }
    String setOperationExpression(ParsedHiveStatement parsedStatement, String normalizedStatement, int selectIndex) {
        List<String> expressions = parsedStatement.getSetOperationExpressionsBySelectIndex().get(selectIndex);
        if (expressions == null || expressions.isEmpty()) {
            return "";
        }
        if (expressions.size() == 1) {
            return expressions.get(0);
        }
        return setOperationFunctionName(normalizedStatement) + "(" + HiveSqlTextUtils.joinWithComma(expressions) + ")";
    }
    String setOperationFunctionName(String normalizedStatement) {
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
    boolean hasCompleteSetOperationProjection(ParsedHiveStatement parsedStatement, int selectItemCount) {
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
    Map<String, Map<String, ColumnLineageProjection>> extractStatementProjectionTables(String statement) {
        Map<String, Map<String, ColumnLineageProjection>> projections = new HashMap<>();
        CteExtraction cteExtraction = extractCtes(statement);
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            projections.putAll(extractInlineProjectionTables(cteBlock.getSql(), projections));
            projections.put(cteBlock.getName().toLowerCase(Locale.ROOT), extractProjectionTable(cteBlock.getSql(), projections));
        }
        projections.putAll(extractInlineProjectionTables(cteExtraction.getRootSql(), projections));
        return projections;
    }
    Map<String, Map<String, ColumnLineageProjection>> bindPhysicalProjectionSources(
        Map<String, Map<String, ColumnLineageProjection>> projections,
        Map<String, String> physicalAliases,
        HiveStatementDatabaseBinder binder
    ) {
        Map<String, Map<String, ColumnLineageProjection>> bound = new HashMap<>();
        for (Map.Entry<String, Map<String, ColumnLineageProjection>> table : projections.entrySet()) {
            Map<String, ColumnLineageProjection> columns = new HashMap<>();
            for (Map.Entry<String, ColumnLineageProjection> column : table.getValue().entrySet()) {
                ColumnLineageProjection value = column.getValue();
                columns.put(column.getKey(), new ColumnLineageProjection(
                    binder.bindColumns(value.getSourceColumns(), physicalAliases),
                    value.getExpression(),
                    value.getLineageType(),
                    value.isUnresolved()
                ));
            }
            bound.put(table.getKey(), columns);
        }
        return bound;
    }
    Map<String, Map<String, ColumnLineageProjection>> extractInlineProjectionTables(
        String sql,
        Map<String, Map<String, ColumnLineageProjection>> existingProjections
    ) {
        Map<String, Map<String, ColumnLineageProjection>> projections = new HashMap<>();
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
                if (depth == 0) {
                    int closeIndex = HiveSqlTextUtils.findMatchingParen(sql, position);
                    if (closeIndex > position) {
                        String body = sql.substring(position + 1, closeIndex).trim();
                        if (isQueryBody(body)) {
                            Map<String, Map<String, ColumnLineageProjection>> combined = combinedProjectionTables(existingProjections, projections);
                            Map<String, Map<String, ColumnLineageProjection>> nested = extractInlineProjectionTables(body, combined);
                            projections.putAll(nested);
                            combined = combinedProjectionTables(existingProjections, projections);
                            String alias = readAliasAfter(sql, closeIndex + 1);
                            if (alias != null) {
                                projections.put(alias, extractProjectionTable(body, combined));
                            }
                        }
                        position = closeIndex + 1;
                        continue;
                    }
                }
                depth++;
                position++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }
            position++;
        }
        return projections;
    }
    Map<String, Map<String, ColumnLineageProjection>> combinedProjectionTables(
        Map<String, Map<String, ColumnLineageProjection>> first,
        Map<String, Map<String, ColumnLineageProjection>> second
    ) {
        Map<String, Map<String, ColumnLineageProjection>> combined = new HashMap<>(first);
        combined.putAll(second);
        return combined;
    }
    boolean isQueryBody(String body) {
        String lower = body.trim().toLowerCase(Locale.ROOT);
        return startsWithKeyword(lower, "select") || startsWithKeyword(lower, "with");
    }
    private boolean startsWithKeyword(String sql, String keyword) {
        return sql.startsWith(keyword)
            && (sql.length() == keyword.length()
            || !HiveSqlTextUtils.isIdentifierCharacter(sql.charAt(keyword.length())));
    }
    String readAliasAfter(String sql, int start) {
        int position = HiveSqlTextUtils.skipWhitespace(sql, start);
        int asEnd = HiveSqlTextUtils.readIdentifier(sql, position);
        if (asEnd > position && "as".equalsIgnoreCase(sql.substring(position, asEnd))) {
            position = HiveSqlTextUtils.skipWhitespace(sql, asEnd);
        }
        int aliasEnd = HiveSqlTextUtils.readIdentifier(sql, position);
        if (aliasEnd <= position) {
            return null;
        }
        String alias = sql.substring(position, aliasEnd);
        return HiveSqlTextUtils.isAliasKeyword(alias) ? null : alias.toLowerCase(Locale.ROOT);
    }
    Map<String, ColumnLineageProjection> extractProjectionTable(
        String sql,
        Map<String, Map<String, ColumnLineageProjection>> existingProjections
    ) {
        CteExtraction cteExtraction = extractCtes(sql);
        Map<String, Map<String, ColumnLineageProjection>> projections = new HashMap<>(existingProjections);
        for (CteBlock cteBlock : cteExtraction.getCteBlocks()) {
            projections.put(cteBlock.getName().toLowerCase(Locale.ROOT), extractProjectionTable(cteBlock.getSql(), projections));
        }
        SetOperationParts setOperationParts = splitTopLevelSetOperation(cteExtraction.getRootSql());
        if (setOperationParts != null) {
            return extractSetOperationProjectionTable(setOperationParts, projections);
        }
        return extractSimpleProjectionTable(cteExtraction.getRootSql(), projections);
    }
    Map<String, ColumnLineageProjection> extractSetOperationProjectionTable(
        SetOperationParts setOperationParts,
        Map<String, Map<String, ColumnLineageProjection>> existingProjections
    ) {
        List<Map<String, ColumnLineageProjection>> branchProjections = new ArrayList<>();
        for (String branchSql : setOperationParts.getBranches()) {
            branchProjections.add(extractProjectionTable(branchSql, existingProjections));
        }
        if (branchProjections.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> outputColumns = new ArrayList<>(branchProjections.get(0).keySet());
        Map<String, ColumnLineageProjection> merged = new LinkedHashMap<>();
        for (int i = 0; i < outputColumns.size(); i++) {
            String outputColumn = outputColumns.get(i);
            List<ColumnRef> sourceColumns = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            List<String> expressions = new ArrayList<>();
            boolean unresolved = false;
            for (Map<String, ColumnLineageProjection> branchProjection : branchProjections) {
                if (i >= branchProjection.size()) {
                    continue;
                }
                ColumnLineageProjection projection = new ArrayList<>(branchProjection.values()).get(i);
                unresolved = unresolved || projection.isUnresolved();
                for (ColumnRef sourceColumn : projection.getSourceColumns()) {
                    addColumn(sourceColumns, seen, sourceColumn);
                }
                expressions.add(projection.getExpression());
            }
            if (unresolved) {
                sourceColumns.clear();
            }
            merged.put(
                outputColumn,
                new ColumnLineageProjection(
                    sourceColumns,
                    setOperationParts.getFunctionName() + "(" + HiveSqlTextUtils.joinWithComma(expressions) + ")",
                    LineageType.DERIVED,
                    unresolved
                )
            );
        }
        return merged;
    }
    Map<String, ColumnLineageProjection> extractSimpleProjectionTable(
        String sql,
        Map<String, Map<String, ColumnLineageProjection>> existingProjections
    ) {
        int selectIndex = HiveSqlTextUtils.topLevelKeywordIndex(sql, "select", 0);
        int fromIndex = HiveSqlTextUtils.topLevelKeywordIndex(sql, "from", selectIndex < 0 ? 0 : selectIndex);
        if (selectIndex < 0 || fromIndex < 0 || fromIndex <= selectIndex) {
            return Collections.emptyMap();
        }
        String selectList = sql.substring(selectIndex + 6, fromIndex);
        Map<String, String> sourceAliases = extractProjectionSourceAliases(sql);
        QualifiedOwnerResolver ownerResolver = QualifiedOwnerResolver.fromAliases(
            sourceAliases,
            targetColumnResolver.extractExplicitSourceAliases(sql)
        );
        String defaultSourceTable = firstAliasTarget(sourceAliases);
        Map<String, ColumnLineageProjection> projections = new LinkedHashMap<>();
        for (String selectItem : HiveSqlTextUtils.splitSelectItems(selectList)) {
            String outputColumn = HiveSqlTextUtils.extractAlias(selectItem);
            String expression = HiveSqlTextUtils.stripAlias(selectItem);
            if (outputColumn == null && isDirectExpression(expression)) {
                outputColumn = HiveSqlTextUtils.columnNameOf(expression);
                if (outputColumn == null) {
                    outputColumn = expression;
                }
            }
            if (outputColumn == null) {
                continue;
            }
            projections.put(
                outputColumn.toLowerCase(Locale.ROOT),
                projectionForExpression(
                    expression,
                    defaultSourceTable,
                    sourceAliases,
                    existingProjections,
                    ownerResolver
                )
            );
        }
        return projections;
    }
    ColumnLineageProjection projectionForExpression(
        String expression,
        String defaultSourceTable,
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> existingProjections,
        QualifiedOwnerResolver ownerResolver
    ) {
        List<ColumnRef> rawSourceColumns = extractSourceColumns(
            expression,
            defaultSourceTable,
            sourceAliases,
            Collections.<String, String>emptyMap(),
            Collections.<String, String>emptyMap(),
            ownerResolver
        );
        List<ColumnRef> expandedSourceColumns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ColumnLineageProjection singleProjection = null;
        boolean unresolved = rawSourceColumns.isEmpty()
            && mayContainColumnReference(expression)
            && !isConstantLineageExpression(expression);
        for (ColumnRef rawSourceColumn : rawSourceColumns) {
            String sourceTable = HiveSqlTextUtils.tableNameOfColumn(rawSourceColumn.getQualifiedName());
            String sourceColumnName = HiveSqlTextUtils.columnNameOf(rawSourceColumn.getQualifiedName());
            ColumnLineageProjection projection = sourceTable == null || sourceColumnName == null
                ? null
                : columnProjection(sourceTable, sourceColumnName, existingProjections);
            if (projection == null) {
                addColumn(expandedSourceColumns, seen, rawSourceColumn);
                continue;
            }
            unresolved = unresolved || projection.isUnresolved();
            singleProjection = rawSourceColumns.size() == 1 ? projection : null;
            for (ColumnRef projectedSourceColumn : projection.getSourceColumns()) {
                addColumn(expandedSourceColumns, seen, projectedSourceColumn);
            }
        }
        String lineageExpression;
        LineageType lineageType;
        if (singleProjection != null) {
            lineageExpression = singleProjection.getExpression();
            lineageType = singleProjection.getLineageType();
        } else if (isDirectExpression(expression) && expandedSourceColumns.size() == 1) {
            lineageExpression = expandedSourceColumns.get(0).getQualifiedName();
            lineageType = LineageType.DIRECT;
        } else {
            lineageExpression = normalizeLineageExpression(expression);
            lineageType = resolveLineageType(lineageExpression, expression, expandedSourceColumns);
        }
        return new ColumnLineageProjection(expandedSourceColumns, lineageExpression, lineageType, unresolved);
    }
    Map<String, String> extractProjectionSourceAliases(String sql) {
        Map<String, String> aliases = targetColumnResolver.extractTopLevelSourceAliases(sql);
        int depth = 0;
        char quote = 0;
        int start = HiveSqlTextUtils.topLevelKeywordIndex(sql, "from", 0);
        if (start < 0) {
            return aliases;
        }
        int position = start;
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
                if (depth == 0) {
                    int closeIndex = HiveSqlTextUtils.findMatchingParen(sql, position);
                    if (closeIndex > position) {
                        String alias = readAliasAfter(sql, closeIndex + 1);
                        if (alias != null) {
                            aliases.put(alias, alias);
                        }
                        position = closeIndex + 1;
                        continue;
                    }
                }
                depth++;
                position++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }
            position++;
        }
        return aliases;
    }
    SetOperationParts splitTopLevelSetOperation(String sql) {
        List<String> branches = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int branchStart = 0;
        String functionName = null;
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
            String operator = depth == 0 ? setOperatorAt(sql, position) : null;
            if (operator != null) {
                branches.add(sql.substring(branchStart, position).trim());
                int next = HiveSqlTextUtils.skipWhitespace(sql, position + operator.length());
                if ("union".equals(operator) && HiveSqlTextUtils.matchesKeywordAt(sql, next, "all")) {
                    functionName = "union_all";
                    branchStart = HiveSqlTextUtils.skipWhitespace(sql, next + 3);
                } else {
                    functionName = "union".equals(operator) ? "union_distinct" : operator;
                    branchStart = next;
                }
                position = branchStart;
                continue;
            }
            position++;
        }
        if (functionName == null) {
            return null;
        }
        branches.add(sql.substring(branchStart).trim());
        return new SetOperationParts(functionName, branches);
    }
    private String setOperatorAt(String sql, int position) {
        String[] operators = {"union", "intersect", "except"};
        for (String operator : operators) {
            if (HiveSqlTextUtils.matchesKeywordAt(sql, position, operator)) {
                return operator;
            }
        }
        return null;
    }
    List<ColumnRef> extractSetOperationSourceColumns(
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
            HiveTableMetadata metadata = targetColumnResolver.loadTableMetadata(request, inputObject.getQualifiedName(), warnings, statementIndex);
            if (metadata == null || containsColumn(metadata, normalizedColumn)) {
                columns.add(new ColumnRef(inputObject.getQualifiedName() + "." + expression));
            }
        }
        return columns;
    }
    String singleProjectionTable(
        Map<String, String> sourceAliases,
        Map<String, Map<String, ColumnLineageProjection>> statementProjectionTables
    ) {
        Set<String> projectionTables = new LinkedHashSet<>();
        for (String sourceAlias : sourceAliases.values()) {
            if (statementProjectionTables.containsKey(sourceAlias)) {
                projectionTables.add(sourceAlias);
            }
        }
        return projectionTables.size() == 1 ? projectionTables.iterator().next() : null;
    }
    ColumnLineageProjection columnProjection(
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
    String directColumnAliasExpression(String expression, Map<String, String> columnAliasExpressions) {
        DirectColumn directColumn = parseDirectColumn(expression);
        if (directColumn == null) {
            return null;
        }
        if (directColumn.getOwner() != null) {
            return columnAliasExpressions.get((directColumn.getOwner() + "." + directColumn.getColumn()).toLowerCase(Locale.ROOT));
        }
        return columnAliasExpressions.get(directColumn.getColumn().toLowerCase(Locale.ROOT));
    }
    DirectColumn parseDirectColumn(String expression) {
        QualifiedColumnReference reference = QualifiedColumnReference.parse(expression);
        if (reference == null || reference.isStar()) {
            return null;
        }
        return new DirectColumn(reference.getOwner(), reference.getColumn());
    }
    boolean containsColumn(HiveTableMetadata metadata, String normalizedColumn) {
        for (String column : metadata.getColumns()) {
            if (normalizedColumn.equals(column.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
    boolean isDirectExpression(String expression) {
        QualifiedColumnReference reference = QualifiedColumnReference.parse(expression);
        return reference != null && !reference.isStar();
    }
    LineageType resolveLineageType(String lineageExpression, String rawExpression, List<ColumnRef> sourceColumns) {
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
    String normalizeLineageExpression(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        return isLiteralExpression(trimmed) ? "constant(" + trimmed + ")" : trimmed;
    }
    boolean isConstantLineageExpression(String expression) {
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
            List<String> parts = HiveSqlTextUtils.splitSelectItems(inner);
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
    boolean isSetOperationLineageExpression(String expression) {
        return setOperationLineageFunction(expression) != null;
    }
    String setOperationLineageFunction(String expression) {
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
    boolean isLiteralExpression(String expression) {
        return isQuotedLiteral(expression)
            || isNumericLiteral(expression)
            || isKeywordLiteral(expression);
    }
    private boolean isQuotedLiteral(String expression) {
        return expression.matches("'(?:[^']|'')*'")
            || expression.matches("\"(?:[^\"]|\"\")*\"");
    }
    private boolean isNumericLiteral(String expression) {
        return expression.matches("[-+]?\\d+(?:\\.\\d+)?");
    }
    private boolean isKeywordLiteral(String expression) {
        return "true".equalsIgnoreCase(expression)
            || "false".equalsIgnoreCase(expression)
            || "null".equalsIgnoreCase(expression);
    }
    List<ColumnRef> extractSourceColumns(
        String expression,
        String defaultSourceTable,
        Map<String, String> sourceAliases,
        Map<String, String> columnOwners,
        Map<String, String> columnAliasSources,
        QualifiedOwnerResolver ownerResolver
    ) {
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(HiveSqlTextUtils.removeStringLiterals(expression));
        while (matcher.find()) {
            QualifiedColumnReference reference = QualifiedColumnReference.parse(matcher.group(1));
            if (reference == null) {
                continue;
            }
            String ownerName = reference.getOwner();
            String columnName = reference.getColumn();
            if (isFunctionName(expression, matcher.end())
                || (ownerName == null && isKeyword(columnName))) {
                continue;
            }
            if (ownerName == null) {
                String mappedSource = columnAliasSources.get(columnName);
                if (mappedSource != null) {
                    addMappedColumn(columns, mappedSource, defaultSourceTable, sourceAliases);
                    continue;
                }
                String owner = columnOwners.get(columnName);
                columns.add((owner == null ? defaultSourceTable : owner) + "." + columnName);
            } else {
                String mappedSource = columnAliasSources.get(ownerName + "." + columnName);
                if (mappedSource != null) {
                    addMappedColumn(columns, mappedSource, defaultSourceTable, sourceAliases);
                    continue;
                }
                String owner = ownerResolver.resolve(ownerName);
                if (owner != null) {
                    columns.add(owner + "." + columnName);
                }
            }
        }
        List<ColumnRef> refs = new ArrayList<>();
        for (String column : columns) {
            refs.add(new ColumnRef(column));
        }
        return refs;
    }
    void addMappedColumn(Set<String> columns, String mappedSource, String defaultSourceTable, Map<String, String> sourceAliases) {
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
    List<String> extractFunctionNames(String expression) {
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
    boolean isFunctionName(String expression, int tokenEnd) {
        int i = tokenEnd;
        while (i < expression.length() && Character.isWhitespace(expression.charAt(i))) {
            i++;
        }
        return i < expression.length() && expression.charAt(i) == '(';
    }
    boolean mayContainColumnReference(String expression) {
        if (isLiteralExpression(expression == null ? "" : expression.trim())) {
            return false;
        }
        String expressionWithoutLiterals = HiveSqlTextUtils.removeStringLiterals(expression);
        if (DOTTED_IDENTIFIER.matcher(expressionWithoutLiterals).find()) {
            return true;
        }
        Matcher matcher = IDENTIFIER.matcher(expressionWithoutLiterals);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (!isKeyword(token) && !isFunctionName(expression, matcher.end())) {
                return true;
            }
        }
        return false;
    }
    boolean isKeyword(String token) {
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
    private CteExtraction extractCtes(String sql) {
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
    private String firstAliasTarget(Map<String, String> sourceAliases) {
        for (Map.Entry<String, String> alias : sourceAliases.entrySet()) {
            if (alias.getKey().equals(alias.getValue()) || alias.getValue().contains(".")) {
                return alias.getValue();
            }
        }
        return "";
    }
    private void addColumn(List<ColumnRef> columns, Set<String> seen, ColumnRef column) {
        if (seen.add(column.getQualifiedName())) {
            columns.add(column);
        }
    }
    private ParsedHiveStatement parseStatement(String normalizedStatement) {
        try {
            return parserAdapter.parse(normalizedStatement);
        } catch (RuntimeException ex) {
            return new ParsedHiveStatement(StatementType.UNSUPPORTED, null, Collections.<DataObjectRef>emptyList(), normalizedStatement);
        }
    }
    private static final class SetOperationParts {
        private final String functionName;
        private final List<String> branches;

        private SetOperationParts(String functionName, List<String> branches) {
            this.functionName = functionName;
            this.branches = Collections.unmodifiableList(new ArrayList<>(branches));
        }

        private String getFunctionName() {
            return functionName;
        }

        private List<String> getBranches() {
            return branches;
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
