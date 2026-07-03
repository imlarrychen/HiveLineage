package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
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
    private final HiveAstWalker astWalker = new HiveAstWalker();
    private final HiveAstExpressionExtractor expressionExtractor = new HiveAstExpressionExtractor();

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
        if (astWalker.first(root, HiveParser.TOK_ALTERVIEW_AS) != null) {
            return StatementType.ALTER_VIEW_AS_SELECT;
        }
        if (astWalker.first(root, HiveParser.TOK_CREATEVIEW) != null) {
            return StatementType.CREATE_VIEW_AS_SELECT;
        }
        if (astWalker.first(root, HiveParser.TOK_CREATETABLE) != null && astWalker.first(root, HiveParser.TOK_QUERY) != null) {
            return StatementType.CREATE_TABLE_AS_SELECT;
        }
        if (astWalker.first(root, HiveParser.TOK_INSERT) != null && astWalker.first(root, HiveParser.TOK_QUERY) != null) {
            return StatementType.INSERT_SELECT;
        }
        return StatementType.UNSUPPORTED;
    }

    private DataObjectRef resolveOutputObject(ASTNode root, ASTNode mainInsert, StatementType statementType) {
        ASTNode tableName = null;
        if (statementType == StatementType.INSERT_SELECT) {
            ASTNode destinationNode = astWalker.first(mainInsert, HiveParser.TOK_DESTINATION);
            ASTNode tabNode = astWalker.first(destinationNode, HiveParser.TOK_TAB);
            tableName = astWalker.first(tabNode, HiveParser.TOK_TABNAME);
        } else if (statementType == StatementType.CREATE_TABLE_AS_SELECT) {
            ASTNode createNode = astWalker.first(root, HiveParser.TOK_CREATETABLE);
            tableName = astWalker.first(createNode, HiveParser.TOK_TABNAME);
        } else if (statementType == StatementType.CREATE_VIEW_AS_SELECT) {
            ASTNode createViewNode = astWalker.first(root, HiveParser.TOK_CREATEVIEW);
            tableName = astWalker.first(createViewNode, HiveParser.TOK_TABNAME);
        } else if (statementType == StatementType.ALTER_VIEW_AS_SELECT) {
            ASTNode alterViewNode = astWalker.first(root, HiveParser.TOK_ALTERVIEW);
            tableName = astWalker.first(alterViewNode, HiveParser.TOK_TABNAME);
        }
        String qualifiedName = tableName == null ? null : tableNameText(tableName);
        return qualifiedName == null ? null : new DataObjectRef(qualifiedName);
    }

    private ASTNode findMainInsert(ASTNode root, StatementType statementType) {
        ASTNode queryNode;
        if (statementType == StatementType.CREATE_VIEW_AS_SELECT) {
            queryNode = astWalker.first(astWalker.first(root, HiveParser.TOK_CREATEVIEW), HiveParser.TOK_QUERY);
        } else if (statementType == StatementType.ALTER_VIEW_AS_SELECT) {
            queryNode = astWalker.first(astWalker.first(root, HiveParser.TOK_ALTERVIEW_AS), HiveParser.TOK_QUERY);
        } else {
            queryNode = astWalker.first(root, HiveParser.TOK_QUERY);
        }
        return astWalker.directChild(queryNode, HiveParser.TOK_INSERT);
    }

    private List<DataObjectRef> resolveInputObjects(ASTNode root, Set<String> cteAliases) {
        Set<String> sourceNames = new LinkedHashSet<>();
        List<ASTNode> tabRefs = astWalker.collect(root, HiveParser.TOK_TABREF);
        for (ASTNode tabRef : tabRefs) {
            ASTNode tableName = astWalker.first(tabRef, HiveParser.TOK_TABNAME);
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
        ASTNode targetColumnNode = astWalker.first(mainInsert, HiveParser.TOK_TABCOLNAME);
        List<String> targetColumns = targetColumnNode == null ? new ArrayList<String>() : directChildTexts(targetColumnNode);
        if (!targetColumns.isEmpty()) {
            return targetColumns;
        }
        if (statementType == StatementType.CREATE_TABLE_AS_SELECT
            || statementType == StatementType.CREATE_VIEW_AS_SELECT
            || statementType == StatementType.ALTER_VIEW_AS_SELECT) {
            ASTNode selectNode = astWalker.directChild(mainInsert, HiveParser.TOK_SELECT);
            List<String> inferred = resolveSelectAliases(selectNode);
            if (!inferred.isEmpty()) {
                return inferred;
            }
        }
        return selectExpressions.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>();
    }

    private List<String> resolveSelectExpressions(ASTNode mainInsert) {
        ASTNode selectNode = astWalker.directChild(mainInsert, HiveParser.TOK_SELECT);
        if (selectNode == null) {
            return Collections.emptyList();
        }
        List<String> expressions = new ArrayList<>();
        for (int i = 0; i < selectNode.getChildCount(); i++) {
            ASTNode child = (ASTNode) selectNode.getChild(i);
            if (child.getType() == HiveParser.TOK_SELEXPR && child.getChildCount() > 0) {
                expressions.add(expressionExtractor.unparse((ASTNode) child.getChild(0)));
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
            aliases.add(expressionExtractor.inferColumnName((ASTNode) selectExpression.getChild(0)));
        }
        return aliases;
    }

    private Map<String, String> resolveSourceAliases(ASTNode root, Set<String> cteAliases) {
        Map<String, String> aliases = new HashMap<>();
        List<ASTNode> tabRefs = astWalker.collect(root, HiveParser.TOK_TABREF);
        for (ASTNode tabRef : tabRefs) {
            ASTNode tableNameNode = astWalker.first(tabRef, HiveParser.TOK_TABNAME);
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
        List<ASTNode> subqueries = astWalker.collect(root, HiveParser.TOK_SUBQUERY);
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
        ASTNode partSpec = astWalker.first(mainInsert, HiveParser.TOK_PARTSPEC);
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
        List<ASTNode> lateralViews = astWalker.collect(root, HiveParser.TOK_LATERAL_VIEW);
        for (ASTNode lateralView : lateralViews) {
            ASTNode selectNode = astWalker.directChild(lateralView, HiveParser.TOK_SELECT);
            if (selectNode == null) {
                continue;
            }
            for (int i = 0; i < selectNode.getChildCount(); i++) {
                ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
                if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() < 2) {
                    continue;
                }
                ASTNode expression = (ASTNode) selectExpression.getChild(0);
                String sourceColumn = expressionExtractor.firstColumnReference(expression);
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

    private Map<String, String> resolveCteAliasMappings(ASTNode root, Set<String> cteAliases) {
        if (cteAliases.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> aliases = new HashMap<>();
        ASTNode cteNode = astWalker.first(root, HiveParser.TOK_CTE);
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
        ASTNode cteNode = astWalker.first(root, HiveParser.TOK_CTE);
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
