package com.panfan.hivelineage;

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
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.apache.hadoop.hive.ql.parse.ParseException;

public final class HiveParserAstAdapter implements HiveAstParserAdapter {
    private static final Pattern INSERT_TARGET_COLUMNS = Pattern.compile(
        "(?is)^(insert\\s+(?:overwrite\\s+table|into(?:\\s+table)?)\\s+[a-zA-Z_][\\w.]*)(\\s*\\(([^)]*)\\))(\\s+select\\b.*)$"
    );
    private final ParseDriver parseDriver = new ParseDriver();

    @Override
    public ParsedHiveStatement parse(String normalizedSql) {
        try {
            String parserSql = sqlForHiveParser(normalizedSql);
            ASTNode root = parseDriver.parse(parserSql).getTree();
            StatementType statementType = resolveStatementType(root);
            Set<String> cteAliases = collectCteAliases(root);
            ASTNode mainInsert = findMainInsert(root, statementType);
            List<String> selectExpressions = resolveSelectExpressions(mainInsert);
            List<String> targetColumns = resolveTargetColumns(root, mainInsert, statementType, selectExpressions);
            if (targetColumns.isEmpty() && statementType == StatementType.INSERT_SELECT) {
                targetColumns = resolveInsertTargetColumns(normalizedSql);
            }
            return new ParsedHiveStatement(
                statementType,
                resolveOutputObject(root, mainInsert, statementType),
                resolveInputObjects(root, cteAliases),
                targetColumns,
                selectExpressions,
                resolveSourceAliases(root, cteAliases),
                resolveColumnAliasSources(root),
                resolveStaticPartitionValues(mainInsert),
                normalizedSql
            );
        } catch (ParseException ex) {
            throw new IllegalArgumentException("Hive SQL parse failed", ex);
        }
    }

    private String sqlForHiveParser(String normalizedSql) {
        Matcher matcher = INSERT_TARGET_COLUMNS.matcher(normalizedSql.trim());
        if (!matcher.matches()) {
            return normalizedSql;
        }
        return matcher.group(1) + matcher.group(4);
    }

    private List<String> resolveInsertTargetColumns(String normalizedSql) {
        Matcher matcher = INSERT_TARGET_COLUMNS.matcher(normalizedSql.trim());
        if (!matcher.matches()) {
            return Collections.emptyList();
        }
        return splitColumnNames(matcher.group(3));
    }

    private StatementType resolveStatementType(ASTNode root) {
        if (findFirst(root, HiveParser.TOK_ALTERVIEW_AS) != null) {
            return StatementType.ALTER_VIEW_AS_SELECT;
        }
        if (findFirst(root, HiveParser.TOK_CREATEVIEW) != null) {
            return StatementType.CREATE_VIEW_AS_SELECT;
        }
        if (findFirst(root, HiveParser.TOK_CREATETABLE) != null && findFirst(root, HiveParser.TOK_QUERY) != null) {
            return StatementType.CREATE_TABLE_AS_SELECT;
        }
        if (findFirst(root, HiveParser.TOK_INSERT) != null && findFirst(root, HiveParser.TOK_QUERY) != null) {
            return StatementType.INSERT_SELECT;
        }
        return StatementType.UNSUPPORTED;
    }

    private DataObjectRef resolveOutputObject(ASTNode root, ASTNode mainInsert, StatementType statementType) {
        ASTNode tableName = null;
        if (statementType == StatementType.INSERT_SELECT) {
            ASTNode destinationNode = findFirst(mainInsert, HiveParser.TOK_DESTINATION);
            ASTNode tabNode = findFirst(destinationNode, HiveParser.TOK_TAB);
            tableName = findFirst(tabNode, HiveParser.TOK_TABNAME);
        } else if (statementType == StatementType.CREATE_TABLE_AS_SELECT) {
            ASTNode createNode = findFirst(root, HiveParser.TOK_CREATETABLE);
            tableName = findFirst(createNode, HiveParser.TOK_TABNAME);
        } else if (statementType == StatementType.CREATE_VIEW_AS_SELECT) {
            ASTNode createViewNode = findFirst(root, HiveParser.TOK_CREATEVIEW);
            tableName = findFirst(createViewNode, HiveParser.TOK_TABNAME);
        } else if (statementType == StatementType.ALTER_VIEW_AS_SELECT) {
            ASTNode alterViewNode = findFirst(root, HiveParser.TOK_ALTERVIEW);
            tableName = findFirst(alterViewNode, HiveParser.TOK_TABNAME);
        }
        String qualifiedName = tableName == null ? null : tableNameText(tableName);
        return qualifiedName == null ? null : new DataObjectRef(qualifiedName);
    }

    private ASTNode findMainInsert(ASTNode root, StatementType statementType) {
        ASTNode queryNode;
        if (statementType == StatementType.CREATE_VIEW_AS_SELECT) {
            queryNode = findFirst(findFirst(root, HiveParser.TOK_CREATEVIEW), HiveParser.TOK_QUERY);
        } else if (statementType == StatementType.ALTER_VIEW_AS_SELECT) {
            queryNode = findFirst(findFirst(root, HiveParser.TOK_ALTERVIEW_AS), HiveParser.TOK_QUERY);
        } else {
            queryNode = findFirst(root, HiveParser.TOK_QUERY);
        }
        return findDirectChild(queryNode, HiveParser.TOK_INSERT);
    }

    private List<DataObjectRef> resolveInputObjects(ASTNode root, Set<String> cteAliases) {
        Set<String> sourceNames = new LinkedHashSet<>();
        List<ASTNode> tabRefs = new ArrayList<>();
        collect(root, HiveParser.TOK_TABREF, tabRefs);
        for (ASTNode tabRef : tabRefs) {
            ASTNode tableName = findFirst(tabRef, HiveParser.TOK_TABNAME);
            if (tableName != null) {
                String sourceName = tableNameText(tableName);
                if (!cteAliases.contains(sourceName)) {
                    sourceNames.add(sourceName);
                }
            }
        }
        List<DataObjectRef> inputObjects = new ArrayList<>();
        for (String sourceName : sourceNames) {
            inputObjects.add(new DataObjectRef(sourceName));
        }
        return inputObjects;
    }

    private List<String> resolveTargetColumns(
        ASTNode root,
        ASTNode mainInsert,
        StatementType statementType,
        List<String> selectExpressions
    ) {
        ASTNode targetColumnNode = findFirst(mainInsert, HiveParser.TOK_TABCOLNAME);
        List<String> targetColumns = targetColumnNode == null ? new ArrayList<String>() : directChildTexts(targetColumnNode);
        if (!targetColumns.isEmpty()) {
            return targetColumns;
        }
        if (statementType == StatementType.CREATE_TABLE_AS_SELECT
            || statementType == StatementType.CREATE_VIEW_AS_SELECT
            || statementType == StatementType.ALTER_VIEW_AS_SELECT) {
            ASTNode selectNode = findDirectChild(mainInsert, HiveParser.TOK_SELECT);
            List<String> inferred = resolveSelectAliases(selectNode);
            if (!inferred.isEmpty()) {
                return inferred;
            }
        }
        return selectExpressions.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>();
    }

    private List<String> resolveSelectExpressions(ASTNode mainInsert) {
        ASTNode selectNode = findDirectChild(mainInsert, HiveParser.TOK_SELECT);
        if (selectNode == null) {
            return Collections.emptyList();
        }
        List<String> expressions = new ArrayList<>();
        for (int i = 0; i < selectNode.getChildCount(); i++) {
            ASTNode child = (ASTNode) selectNode.getChild(i);
            if (child.getType() == HiveParser.TOK_SELEXPR && child.getChildCount() > 0) {
                expressions.add(unparseExpression((ASTNode) child.getChild(0)));
            }
        }
        return expressions;
    }

    private List<String> resolveSelectAliases(ASTNode selectNode) {
        if (selectNode == null) {
            return Collections.emptyList();
        }
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < selectNode.getChildCount(); i++) {
            ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
            if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() == 0) {
                continue;
            }
            if (selectExpression.getChildCount() > 1) {
                aliases.add(stripIdentifier(selectExpression.getChild(selectExpression.getChildCount() - 1).getText()));
                continue;
            }
            aliases.add(inferColumnName((ASTNode) selectExpression.getChild(0)));
        }
        return aliases;
    }

    private String inferColumnName(ASTNode expression) {
        if (expression.getType() == HiveParser.TOK_TABLE_OR_COL && expression.getChildCount() == 1) {
            return stripIdentifier(expression.getChild(0).getText());
        }
        if (".".equals(expression.getText()) && expression.getChildCount() == 2) {
            return stripIdentifier(expression.getChild(1).getText());
        }
        return unparseExpression(expression);
    }

    private Map<String, String> resolveSourceAliases(ASTNode root, Set<String> cteAliases) {
        Map<String, String> aliases = new HashMap<>();
        List<ASTNode> tabRefs = new ArrayList<>();
        collect(root, HiveParser.TOK_TABREF, tabRefs);
        for (ASTNode tabRef : tabRefs) {
            ASTNode tableNameNode = findFirst(tabRef, HiveParser.TOK_TABNAME);
            if (tableNameNode == null) {
                continue;
            }
            String tableName = tableNameText(tableNameNode);
            if (cteAliases.contains(tableName)) {
                continue;
            }
            aliases.put(tableName, tableName);
            aliases.put(simpleName(tableName), tableName);
            String alias = tableAlias(tabRef, tableNameNode);
            if (alias != null) {
                aliases.put(alias, tableName);
            }
        }
        aliases.putAll(resolveCteAliasMappings(root, cteAliases));
        aliases.putAll(resolveSubqueryAliasMappings(root, cteAliases));
        return aliases;
    }

    private Map<String, String> resolveSubqueryAliasMappings(ASTNode root, Set<String> cteAliases) {
        Map<String, String> aliases = new HashMap<>();
        List<ASTNode> subqueries = new ArrayList<>();
        collect(root, HiveParser.TOK_SUBQUERY, subqueries);
        for (ASTNode subquery : subqueries) {
            if (subquery.getChildCount() < 2) {
                continue;
            }
            String alias = stripIdentifier(subquery.getChild(subquery.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT);
            if (cteAliases.contains(alias)) {
                continue;
            }
            List<DataObjectRef> sources = resolveInputObjects(subquery, cteAliases);
            if (sources.size() == 1) {
                aliases.put(alias, sources.get(0).getQualifiedName());
            }
        }
        return aliases;
    }

    private Map<String, String> resolveStaticPartitionValues(ASTNode mainInsert) {
        ASTNode partSpec = findFirst(mainInsert, HiveParser.TOK_PARTSPEC);
        if (partSpec == null) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < partSpec.getChildCount(); i++) {
            ASTNode partVal = (ASTNode) partSpec.getChild(i);
            if (partVal.getType() != HiveParser.TOK_PARTVAL || partVal.getChildCount() < 2) {
                continue;
            }
            String columnName = stripIdentifier(partVal.getChild(0).getText()).toLowerCase(Locale.ROOT);
            String value = partVal.getChild(1).getText();
            values.put(columnName, value);
        }
        return values;
    }

    private Map<String, String> resolveColumnAliasSources(ASTNode root) {
        Map<String, String> aliases = new HashMap<>();
        List<ASTNode> lateralViews = new ArrayList<>();
        collect(root, HiveParser.TOK_LATERAL_VIEW, lateralViews);
        for (ASTNode lateralView : lateralViews) {
            ASTNode selectNode = findDirectChild(lateralView, HiveParser.TOK_SELECT);
            if (selectNode == null) {
                continue;
            }
            for (int i = 0; i < selectNode.getChildCount(); i++) {
                ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
                if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() < 2) {
                    continue;
                }
                ASTNode expression = (ASTNode) selectExpression.getChild(0);
                String sourceColumn = firstColumnReference(expression);
                if (sourceColumn == null) {
                    continue;
                }
                for (int j = 1; j < selectExpression.getChildCount(); j++) {
                    ASTNode child = (ASTNode) selectExpression.getChild(j);
                    if (child.getType() == HiveParser.Identifier) {
                        aliases.put(stripIdentifier(child.getText()).toLowerCase(Locale.ROOT), sourceColumn);
                    }
                }
            }
        }
        return aliases;
    }

    private String firstColumnReference(ASTNode node) {
        if (node == null) {
            return null;
        }
        if (node.getType() == HiveParser.TOK_TABLE_OR_COL && node.getChildCount() == 1) {
            return stripIdentifier(node.getChild(0).getText()).toLowerCase(Locale.ROOT);
        }
        if (".".equals(node.getText()) && node.getChildCount() == 2) {
            return unparseExpression(node).toLowerCase(Locale.ROOT);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String found = firstColumnReference((ASTNode) node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private Map<String, String> resolveCteAliasMappings(ASTNode root, Set<String> cteAliases) {
        if (cteAliases.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> aliases = new HashMap<>();
        ASTNode cteNode = findFirst(root, HiveParser.TOK_CTE);
        if (cteNode == null) {
            return aliases;
        }
        for (int i = 0; i < cteNode.getChildCount(); i++) {
            ASTNode subquery = (ASTNode) cteNode.getChild(i);
            if (subquery.getType() != HiveParser.TOK_SUBQUERY || subquery.getChildCount() < 2) {
                continue;
            }
            String cteAlias = stripIdentifier(subquery.getChild(subquery.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT);
            List<DataObjectRef> sources = resolveInputObjects(subquery, cteAliases);
            if (sources.size() == 1) {
                aliases.put(cteAlias, sources.get(0).getQualifiedName());
            }
        }
        return aliases;
    }

    private Set<String> collectCteAliases(ASTNode root) {
        Set<String> aliases = new LinkedHashSet<>();
        ASTNode cteNode = findFirst(root, HiveParser.TOK_CTE);
        if (cteNode == null) {
            return aliases;
        }
        for (int i = 0; i < cteNode.getChildCount(); i++) {
            ASTNode subquery = (ASTNode) cteNode.getChild(i);
            if (subquery.getType() == HiveParser.TOK_SUBQUERY && subquery.getChildCount() > 1) {
                aliases.add(stripIdentifier(subquery.getChild(subquery.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT));
            }
        }
        return aliases;
    }

    private ASTNode findFirst(ASTNode node, int tokenType) {
        if (node == null) {
            return null;
        }
        if (node.getType() == tokenType) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ASTNode found = findFirst((ASTNode) node.getChild(i), tokenType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ASTNode findDirectChild(ASTNode node, int tokenType) {
        if (node == null) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ASTNode child = (ASTNode) node.getChild(i);
            if (child.getType() == tokenType) {
                return child;
            }
        }
        return null;
    }

    private void collect(ASTNode node, int tokenType, List<ASTNode> result) {
        if (node == null) {
            return;
        }
        if (node.getType() == tokenType) {
            result.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collect((ASTNode) node.getChild(i), tokenType, result);
        }
    }

    private String tableNameText(ASTNode tableNameNode) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < tableNameNode.getChildCount(); i++) {
            parts.add(stripIdentifier(tableNameNode.getChild(i).getText()));
        }
        if (parts.isEmpty()) {
            return stripIdentifier(tableNameNode.getText());
        }
        return join(parts).toLowerCase(Locale.ROOT);
    }

    private List<String> directChildTexts(ASTNode node) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) {
            texts.add(stripIdentifier(node.getChild(i).getText()).toLowerCase(Locale.ROOT));
        }
        return texts;
    }

    private List<String> splitColumnNames(String rawColumns) {
        if (rawColumns == null || rawColumns.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> columns = new ArrayList<>();
        for (String column : rawColumns.split(",")) {
            String trimmed = stripIdentifier(column.trim()).toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                columns.add(trimmed);
            }
        }
        return columns;
    }

    private String tableAlias(ASTNode tabRef, ASTNode tableNameNode) {
        for (int i = 0; i < tabRef.getChildCount(); i++) {
            ASTNode child = (ASTNode) tabRef.getChild(i);
            if (child == tableNameNode) {
                continue;
            }
            if (child.getType() == HiveParser.Identifier) {
                return stripIdentifier(child.getText()).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String simpleName(String qualifiedName) {
        int dotIndex = qualifiedName.lastIndexOf('.');
        return dotIndex < 0 ? qualifiedName : qualifiedName.substring(dotIndex + 1);
    }

    private String unparseExpression(ASTNode expression) {
        if (expression == null) {
            return "";
        }
        String text = expression.getText();
        if (expression.getType() == HiveParser.TOK_ALLCOLREF) {
            return "*";
        }
        if (expression.getType() == HiveParser.TOK_TABLE_OR_COL && expression.getChildCount() == 1) {
            return stripIdentifier(expression.getChild(0).getText());
        }
        if (".".equals(text) && expression.getChildCount() == 2) {
            return unparseExpression((ASTNode) expression.getChild(0)) + "." + stripIdentifier(expression.getChild(1).getText());
        }
        if (expression.getType() == HiveParser.TOK_FUNCTION && expression.getChildCount() > 0) {
            String functionName = stripIdentifier(expression.getChild(0).getText());
            List<String> args = new ArrayList<>();
            for (int i = 1; i < expression.getChildCount(); i++) {
                args.add(unparseExpression((ASTNode) expression.getChild(i)));
            }
            return functionName + "(" + joinWithComma(args) + ")";
        }
        if (expression.getChildCount() == 2 && isBinaryOperator(text)) {
            return unparseExpression((ASTNode) expression.getChild(0)) + " " + text + " " + unparseExpression((ASTNode) expression.getChild(1));
        }
        if (expression.getChildCount() == 1 && isUnaryOperator(text)) {
            return text + unparseExpression((ASTNode) expression.getChild(0));
        }
        if (expression.getChildCount() == 0) {
            return stripIdentifier(text);
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < expression.getChildCount(); i++) {
            parts.add(unparseExpression((ASTNode) expression.getChild(i)));
        }
        return text + "(" + joinWithComma(parts) + ")";
    }

    private boolean isBinaryOperator(String text) {
        return "+".equals(text) || "-".equals(text) || "*".equals(text) || "/".equals(text) || "%".equals(text);
    }

    private boolean isUnaryOperator(String text) {
        return "+".equals(text) || "-".equals(text) || "~".equals(text);
    }

    private String joinWithComma(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private String stripIdentifier(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '`' && trimmed.charAt(trimmed.length() - 1) == '`') {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String join(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append('.');
            }
            builder.append(part);
        }
        return builder.toString();
    }
}
