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
            ASTNode mainSetOperation = findMainSetOperation(root, mainInsert);
            List<String> selectExpressions = resolveSelectExpressions(root, mainInsert);
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
                resolveColumnAliasSources(root, cteAliases),
                resolveColumnAliasExpressions(root, cteAliases),
                resolveSetOperationSourceColumns(mainSetOperation, cteAliases),
                resolveSetOperationExpressions(mainSetOperation, cteAliases),
                resolveStaticPartitionValues(mainInsert),
                resolveJoinConditions(root, cteAliases),
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
            ASTNode selectNode = directSelectChild(mainInsert);
            List<String> inferred = resolveSelectAliases(selectNode);
            if (!inferred.isEmpty()) {
                return inferred;
            }
        }
        return selectExpressions.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>();
    }

    private List<String> resolveSelectExpressions(ASTNode root, ASTNode mainInsert) {
        List<String> expressions = resolveDirectSelectExpressions(mainInsert);
        if (expressions.size() == 1 && "TOK_SETCOLREF".equalsIgnoreCase(expressions.get(0))) {
            ASTNode unionNode = findUnionNode(root);
            if (unionNode != null) {
                ASTNode firstUnionInsert = firstUnionBranchInsert(unionNode);
                List<String> unionBranchExpressions = resolveDirectSelectExpressions(firstUnionInsert);
                if (!unionBranchExpressions.isEmpty()) {
                    return unionBranchExpressions;
                }
            }
        }
        return expressions;
    }

    private ASTNode findMainSetOperation(ASTNode root, ASTNode mainInsert) {
        List<String> expressions = resolveDirectSelectExpressions(mainInsert);
        if (expressions.size() == 1 && "TOK_SETCOLREF".equalsIgnoreCase(expressions.get(0))) {
            return findUnionNode(root);
        }
        return null;
    }

    private List<String> resolveDirectSelectExpressions(ASTNode insertNode) {
        if (insertNode == null) {
            return Collections.emptyList();
        }
        ASTNode selectNode = directSelectChild(insertNode);
        if (selectNode == null) {
            return Collections.emptyList();
        }
        return resolveSelectExpressionsFromSelectNode(selectNode);
    }

    private ASTNode directSelectChild(ASTNode insertNode) {
        ASTNode selectNode = astWalker.directChild(insertNode, HiveParser.TOK_SELECT);
        if (selectNode != null) {
            return selectNode;
        }
        return astWalker.directChild(insertNode, HiveParser.TOK_SELECTDI);
    }

    private List<String> resolveSelectExpressionsFromSelectNode(ASTNode selectNode) {
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

    private ASTNode findUnionNode(ASTNode node) {
        if (node == null) {
            return null;
        }
        String text = node.getText();
        if (text != null && text.toUpperCase(Locale.ROOT).startsWith("TOK_UNION")) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ASTNode found = findUnionNode((ASTNode) node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ASTNode firstUnionBranchInsert(ASTNode unionNode) {
        if (unionNode == null) {
            return null;
        }
        for (int i = 0; i < unionNode.getChildCount(); i++) {
            ASTNode child = (ASTNode) unionNode.getChild(i);
            if (child.getType() == HiveParser.TOK_QUERY) {
                ASTNode insertNode = astWalker.directChild(child, HiveParser.TOK_INSERT);
                if (insertNode != null) {
                    return insertNode;
                }
            }
            ASTNode nestedUnionInsert = firstUnionBranchInsert(findUnionNode(child));
            if (nestedUnionInsert != null) {
                return nestedUnionInsert;
            }
        }
        return null;
    }

    private Map<Integer, List<String>> resolveSetOperationSourceColumns(ASTNode root, Set<String> cteAliases) {
        ASTNode unionNode = findUnionNode(root);
        if (unionNode == null) {
            return Collections.emptyMap();
        }
        Map<Integer, LinkedHashSet<String>> collected = new HashMap<>();
        collectSetOperationSourceColumns(unionNode, cteAliases, collected);
        Map<Integer, List<String>> result = new HashMap<>();
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : collected.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    private Map<Integer, List<String>> resolveSetOperationExpressions(ASTNode root, Set<String> cteAliases) {
        ASTNode unionNode = findUnionNode(root);
        if (unionNode == null) {
            return Collections.emptyMap();
        }
        Map<Integer, LinkedHashSet<String>> collected = new HashMap<>();
        collectSetOperationExpressions(unionNode, cteAliases, collected);
        Map<Integer, List<String>> result = new HashMap<>();
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : collected.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    private void collectSetOperationExpressions(
        ASTNode node,
        Set<String> cteAliases,
        Map<Integer, LinkedHashSet<String>> collected
    ) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.TOK_QUERY) {
            ASTNode insertNode = astWalker.directChild(node, HiveParser.TOK_INSERT);
            ASTNode selectNode = directSelectChild(insertNode);
            if (selectNode != null) {
                Map<String, String> branchAliases = resolveSourceAliases(node, cteAliases);
                String defaultSource = defaultSourceTable(node, cteAliases);
                for (int i = 0; i < selectNode.getChildCount(); i++) {
                    ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
                    if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() == 0) {
                        continue;
                    }
                    String expression = renderSetOperationExpression(
                        (ASTNode) selectExpression.getChild(0),
                        branchAliases,
                        defaultSource
                    );
                    if (!expression.isEmpty()) {
                        LinkedHashSet<String> positionExpressions = collected.get(i);
                        if (positionExpressions == null) {
                            positionExpressions = new LinkedHashSet<>();
                            collected.put(i, positionExpressions);
                        }
                        positionExpressions.add(expression);
                    }
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSetOperationExpressions((ASTNode) node.getChild(i), cteAliases, collected);
        }
    }

    private void collectSetOperationSourceColumns(
        ASTNode node,
        Set<String> cteAliases,
        Map<Integer, LinkedHashSet<String>> collected
    ) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.TOK_QUERY) {
            ASTNode insertNode = astWalker.directChild(node, HiveParser.TOK_INSERT);
            ASTNode selectNode = directSelectChild(insertNode);
            if (selectNode != null) {
                Map<String, String> branchAliases = resolveSourceAliases(node, cteAliases);
                String defaultSource = defaultSourceTable(node, cteAliases);
                for (int i = 0; i < selectNode.getChildCount(); i++) {
                    ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
                    if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() == 0) {
                        continue;
                    }
                    LinkedHashSet<String> expressionSources = new LinkedHashSet<>();
                    collectExpressionSourceColumns((ASTNode) selectExpression.getChild(0), branchAliases, defaultSource, expressionSources);
                    if (!expressionSources.isEmpty()) {
                        LinkedHashSet<String> positionSources = collected.get(i);
                        if (positionSources == null) {
                            positionSources = new LinkedHashSet<>();
                            collected.put(i, positionSources);
                        }
                        positionSources.addAll(expressionSources);
                    }
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSetOperationSourceColumns((ASTNode) node.getChild(i), cteAliases, collected);
        }
    }

    private void collectExpressionSourceColumns(
        ASTNode node,
        Map<String, String> branchAliases,
        String defaultSource,
        Set<String> collected
    ) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.TOK_TABLE_OR_COL && node.getChildCount() == 1) {
            if (defaultSource != null) {
                collected.add(defaultSource + "." + stripIdentifier(node.getChild(0).getText()));
            }
            return;
        }
        if (".".equals(node.getText()) && node.getChildCount() == 2) {
            String owner = expressionExtractor.unparse((ASTNode) node.getChild(0)).toLowerCase(Locale.ROOT);
            String resolvedOwner = branchAliases.get(owner);
            collected.add((resolvedOwner == null ? owner : resolvedOwner) + "." + stripIdentifier(node.getChild(1).getText()));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectExpressionSourceColumns((ASTNode) node.getChild(i), branchAliases, defaultSource, collected);
        }
    }

    private String defaultSourceTable(ASTNode queryNode, Set<String> cteAliases) {
        List<DataObjectRef> sources = resolveInputObjects(queryNode, cteAliases);
        return sources.size() == 1 ? sources.get(0).getQualifiedName() : null;
    }

    private String renderSetOperationExpression(
        ASTNode node,
        Map<String, String> branchAliases,
        String defaultSource
    ) {
        if (node == null) {
            return "";
        }
        if (node.getType() == HiveParser.TOK_TABLE_OR_COL && node.getChildCount() == 1) {
            String column = stripIdentifier(node.getChild(0).getText());
            return defaultSource == null ? column : defaultSource + "." + column;
        }
        if (".".equals(node.getText()) && node.getChildCount() == 2) {
            String owner = expressionExtractor.unparse((ASTNode) node.getChild(0)).toLowerCase(Locale.ROOT);
            String resolvedOwner = branchAliases.get(owner);
            return (resolvedOwner == null ? owner : resolvedOwner) + "." + stripIdentifier(node.getChild(1).getText());
        }
        if (isLiteralNode(node)) {
            return "constant(" + node.getText() + ")";
        }
        return expressionExtractor.unparse(node);
    }

    private boolean isLiteralNode(ASTNode node) {
        int type = node.getType();
        return type == HiveParser.StringLiteral
            || type == HiveParser.Number
            || type == HiveParser.NumberLiteral
            || type == HiveParser.IntegralLiteral
            || type == HiveParser.KW_TRUE
            || type == HiveParser.KW_FALSE
            || type == HiveParser.KW_NULL;
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
        Map<String, String> aliases = resolveTableSourceAliases(root, cteAliases);
        aliases.putAll(resolveCteAliasMappings(root, cteAliases));
        aliases.putAll(resolveSubqueryAliasMappings(root, cteAliases));
        return aliases;
    }

    private Map<String, String> resolveTableSourceAliases(ASTNode root, Set<String> cteAliases) {
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

    private Map<String, String> resolveColumnAliasSources(ASTNode root, Set<String> cteAliases) {
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
        aliases.putAll(resolveSubqueryColumnAliasSources(root, cteAliases));
        return aliases;
    }

    private Map<String, String> resolveColumnAliasExpressions(ASTNode root, Set<String> cteAliases) {
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
                String expression = expressionExtractor.unparse((ASTNode) selectExpression.getChild(0));
                for (int j = 1; j < selectExpression.getChildCount(); j++) {
                    ASTNode child = (ASTNode) selectExpression.getChild(j);
                    if (child.getType() == HiveParser.Identifier) {
                        aliases.put(stripIdentifier(child.getText()).toLowerCase(Locale.ROOT), expression);
                    }
                }
            }
        }
        aliases.putAll(resolveSubqueryColumnAliasExpressions(root, cteAliases));
        return aliases;
    }

    private Map<String, String> resolveSubqueryColumnAliasSources(ASTNode root, Set<String> cteAliases) {
        Map<String, String> aliases = new HashMap<>();
        for (SubqueryColumnProjection projection : resolveSubqueryColumnProjections(root, cteAliases)) {
            if (projection.getSourceColumn() == null) {
                continue;
            }
            aliases.put(projection.getColumnName(), projection.getSourceColumn());
            aliases.put(projection.getQualifiedColumnName(), projection.getSourceColumn());
        }
        return aliases;
    }

    private Map<String, String> resolveSubqueryColumnAliasExpressions(ASTNode root, Set<String> cteAliases) {
        Map<String, String> aliases = new HashMap<>();
        for (SubqueryColumnProjection projection : resolveSubqueryColumnProjections(root, cteAliases)) {
            aliases.put(projection.getColumnName(), projection.getExpression());
            aliases.put(projection.getQualifiedColumnName(), projection.getExpression());
        }
        return aliases;
    }

    private List<SubqueryColumnProjection> resolveSubqueryColumnProjections(ASTNode root, Set<String> cteAliases) {
        List<SubqueryColumnProjection> projections = new ArrayList<>();
        List<ASTNode> subqueries = astWalker.collect(root, HiveParser.TOK_SUBQUERY);
        for (ASTNode subquery : subqueries) {
            if (subquery.getChildCount() < 2) {
                continue;
            }
            String alias = stripIdentifier(subquery.getChild(subquery.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT);
            if (cteAliases.contains(alias)) {
                continue;
            }
            ASTNode queryNode = astWalker.directChild(subquery, HiveParser.TOK_QUERY);
            ASTNode insertNode = astWalker.directChild(queryNode, HiveParser.TOK_INSERT);
            ASTNode selectNode = directSelectChild(insertNode);
            if (selectNode == null) {
                continue;
            }
            Map<String, String> sourceAliases = resolveTableSourceAliases(subquery, cteAliases);
            String defaultSource = defaultSourceTable(subquery, cteAliases);
            for (int i = 0; i < selectNode.getChildCount(); i++) {
                ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
                if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() == 0) {
                    continue;
                }
                ASTNode expressionNode = (ASTNode) selectExpression.getChild(0);
                String columnName = selectExpression.getChildCount() > 1
                    ? stripIdentifier(selectExpression.getChild(selectExpression.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT)
                    : expressionExtractor.inferColumnName(expressionNode).toLowerCase(Locale.ROOT);
                LinkedHashSet<String> expressionSources = new LinkedHashSet<>();
                collectExpressionSourceColumns(expressionNode, sourceAliases, defaultSource, expressionSources);
                String expression = expressionExtractor.unparse(expressionNode);
                projections.add(new SubqueryColumnProjection(
                    alias,
                    columnName,
                    expression,
                    expressionSources.isEmpty() || "count(*)".equalsIgnoreCase(expression) ? null : expressionSources.iterator().next()
                ));
            }
        }
        return projections;
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

    private List<ParsedJoinCondition> resolveJoinConditions(ASTNode root, Set<String> cteAliases) {
        List<ParsedJoinCondition> conditions = new ArrayList<>();
        int[] joinIndex = new int[] {1};
        ASTNode cteNode = astWalker.first(root, HiveParser.TOK_CTE);
        if (cteNode != null) {
            for (int i = 0; i < cteNode.getChildCount(); i++) {
                ASTNode subquery = (ASTNode) cteNode.getChild(i);
                if (subquery.getType() != HiveParser.TOK_SUBQUERY || subquery.getChildCount() < 2) {
                    continue;
                }
                String cteAlias = stripIdentifier(subquery.getChild(subquery.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT);
                ASTNode queryNode = astWalker.directChild(subquery, HiveParser.TOK_QUERY);
                collectJoinConditionsFromQuery(queryNode, "cte." + cteAlias, cteAliases, joinIndex, conditions);
                collectNestedSubqueryJoinConditionsFromChildren(queryNode, "cte." + cteAlias, cteAliases, joinIndex, conditions);
            }
        }
        ASTNode rootQuery = astWalker.first(root, HiveParser.TOK_QUERY);
        collectJoinConditionsFromQuery(rootQuery, "root", cteAliases, joinIndex, conditions);
        collectNestedSubqueryJoinConditionsFromChildren(rootQuery, "root", cteAliases, joinIndex, conditions);
        return conditions;
    }

    private void collectNestedSubqueryJoinConditionsFromChildren(
        ASTNode queryNode,
        String parentPath,
        Set<String> cteAliases,
        int[] joinIndex,
        List<ParsedJoinCondition> conditions
    ) {
        if (queryNode == null) {
            return;
        }
        int[] subqueryIndex = new int[] {1};
        for (int i = 0; i < queryNode.getChildCount(); i++) {
            collectNestedSubqueryJoinConditions(
                (ASTNode) queryNode.getChild(i),
                parentPath,
                cteAliases,
                joinIndex,
                conditions,
                subqueryIndex,
                false
            );
        }
    }

    private void collectNestedSubqueryJoinConditions(
        ASTNode node,
        String parentPath,
        Set<String> cteAliases,
        int[] joinIndex,
        List<ParsedJoinCondition> conditions,
        int[] subqueryIndex,
        boolean inWhere
    ) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.TOK_CTE) {
            return;
        }
        boolean childInWhere = inWhere || node.getType() == HiveParser.TOK_WHERE;
        if (node.getType() == HiveParser.TOK_QUERY) {
            String queryBlockPath = childInWhere
                ? parentPath + ".where.exists[" + subqueryIndex[0]++ + "]"
                : parentPath + ".subquery[" + subqueryIndex[0]++ + "]";
            collectJoinConditionsFromQuery(node, queryBlockPath, cteAliases, joinIndex, conditions);
            for (int i = 0; i < node.getChildCount(); i++) {
                collectNestedSubqueryJoinConditions(
                    (ASTNode) node.getChild(i),
                    queryBlockPath,
                    cteAliases,
                    joinIndex,
                    conditions,
                    new int[] {1},
                    false
                );
            }
            return;
        }
        if (node.getType() == HiveParser.TOK_SUBQUERY) {
            ASTNode queryNode = astWalker.directChild(node, HiveParser.TOK_QUERY);
            if (queryNode != null) {
                String queryBlockPath = childInWhere
                    ? parentPath + ".where.exists[" + subqueryIndex[0]++ + "]"
                    : parentPath + ".subquery[" + subqueryIndex[0]++ + "]";
                collectJoinConditionsFromQuery(queryNode, queryBlockPath, cteAliases, joinIndex, conditions);
                collectNestedSubqueryJoinConditions(queryNode, queryBlockPath, cteAliases, joinIndex, conditions, new int[] {1}, false);
                return;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNestedSubqueryJoinConditions(
                (ASTNode) node.getChild(i),
                parentPath,
                cteAliases,
                joinIndex,
                conditions,
                subqueryIndex,
                childInWhere
            );
        }
    }

    private void collectJoinConditionsFromQuery(
        ASTNode queryNode,
        String queryBlockPath,
        Set<String> cteAliases,
        int[] joinIndex,
        List<ParsedJoinCondition> conditions
    ) {
        if (queryNode == null) {
            return;
        }
        ASTNode fromNode = astWalker.directChild(queryNode, HiveParser.TOK_FROM);
        if (fromNode == null) {
            return;
        }
        Map<String, String> directAliases = resolveDirectSourceAliases(queryNode, cteAliases);
        collectJoinConditionsFromNode(fromNode, queryBlockPath, directAliases, joinIndex, conditions);
    }

    private void collectJoinConditionsFromNode(
        ASTNode node,
        String queryBlockPath,
        Map<String, String> directAliases,
        int[] joinIndex,
        List<ParsedJoinCondition> conditions
    ) {
        if (node == null) {
            return;
        }
        if (isJoinNode(node)) {
            ASTNode conditionNode = joinConditionNode(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                ASTNode child = (ASTNode) node.getChild(i);
                if (isJoinNode(child)) {
                    collectJoinConditionsFromNode(child, queryBlockPath, directAliases, joinIndex, conditions);
                }
            }
            if (conditionNode != null) {
                String expression = expressionExtractor.unparse(conditionNode);
                List<String> directColumns = collectDirectColumnReferences(conditionNode);
                List<String> directObjects = directObjectsForColumns(directColumns, directAliases);
                conditions.add(new ParsedJoinCondition(
                    joinIndex[0]++,
                    queryBlockPath,
                    joinTypeCode(node),
                    joinTypeName(node),
                    directObjects,
                    directColumns,
                    expression
                ));
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                ASTNode child = (ASTNode) node.getChild(i);
                if (child != conditionNode && !isJoinNode(child)) {
                    collectJoinConditionsFromNode(child, queryBlockPath, directAliases, joinIndex, conditions);
                }
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectJoinConditionsFromNode((ASTNode) node.getChild(i), queryBlockPath, directAliases, joinIndex, conditions);
        }
    }

    private boolean isJoinNode(ASTNode node) {
        int type = node.getType();
        return type == HiveParser.TOK_JOIN
            || type == HiveParser.TOK_LEFTOUTERJOIN
            || type == HiveParser.TOK_RIGHTOUTERJOIN
            || type == HiveParser.TOK_FULLOUTERJOIN
            || type == HiveParser.TOK_LEFTSEMIJOIN
            || type == HiveParser.TOK_LEFTANTISEMIJOIN;
    }

    private ASTNode joinConditionNode(ASTNode joinNode) {
        for (int i = joinNode.getChildCount() - 1; i >= 0; i--) {
            ASTNode child = (ASTNode) joinNode.getChild(i);
            if (child.getType() != HiveParser.TOK_TABREF
                && child.getType() != HiveParser.TOK_SUBQUERY
                && !isJoinNode(child)) {
                return child;
            }
        }
        return null;
    }

    private int joinTypeCode(ASTNode joinNode) {
        int type = joinNode.getType();
        if (type == HiveParser.TOK_JOIN) {
            return 1;
        }
        if (type == HiveParser.TOK_LEFTOUTERJOIN) {
            return 2;
        }
        if (type == HiveParser.TOK_RIGHTOUTERJOIN) {
            return 3;
        }
        if (type == HiveParser.TOK_FULLOUTERJOIN) {
            return 4;
        }
        if (type == HiveParser.TOK_LEFTSEMIJOIN) {
            return 5;
        }
        if (type == HiveParser.TOK_LEFTANTISEMIJOIN) {
            return 6;
        }
        return 0;
    }

    private String joinTypeName(ASTNode joinNode) {
        int type = joinNode.getType();
        if (type == HiveParser.TOK_JOIN) {
            return "INNER_JOIN";
        }
        if (type == HiveParser.TOK_LEFTOUTERJOIN) {
            return "LEFT_JOIN";
        }
        if (type == HiveParser.TOK_RIGHTOUTERJOIN) {
            return "RIGHT_JOIN";
        }
        if (type == HiveParser.TOK_FULLOUTERJOIN) {
            return "FULL_JOIN";
        }
        if (type == HiveParser.TOK_LEFTSEMIJOIN) {
            return "LEFT_SEMI_JOIN";
        }
        if (type == HiveParser.TOK_LEFTANTISEMIJOIN) {
            return "LEFT_ANTI_JOIN";
        }
        return "UNKNOWN";
    }

    private Map<String, String> resolveDirectSourceAliases(ASTNode queryNode, Set<String> cteAliases) {
        Map<String, String> aliases = new HashMap<>();
        List<ASTNode> tabRefs = astWalker.collect(queryNode, HiveParser.TOK_TABREF);
        for (ASTNode tabRef : tabRefs) {
            ASTNode tableNameNode = astWalker.first(tabRef, HiveParser.TOK_TABNAME);
            if (tableNameNode == null) {
                continue;
            }
            String tableName = tableNameText(tableNameNode);
            aliases.put(tableName, tableName);
            aliases.put(simpleName(tableName), tableName);
            String alias = tableAlias(tabRef, tableNameNode);
            if (alias != null) {
                aliases.put(alias, tableName);
            }
        }
        List<ASTNode> subqueries = astWalker.collect(queryNode, HiveParser.TOK_SUBQUERY);
        for (ASTNode subquery : subqueries) {
            if (subquery.getChildCount() < 2) {
                continue;
            }
            String alias = stripIdentifier(subquery.getChild(subquery.getChildCount() - 1).getText()).toLowerCase(Locale.ROOT);
            aliases.put(alias, alias);
        }
        for (String cteAlias : cteAliases) {
            aliases.put(cteAlias, cteAlias);
        }
        return aliases;
    }

    private List<String> collectDirectColumnReferences(ASTNode expressionNode) {
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        collectDirectColumnReferences(expressionNode, columns);
        return new ArrayList<>(columns);
    }

    private void collectDirectColumnReferences(ASTNode node, Set<String> columns) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.TOK_TABLE_OR_COL && node.getChildCount() == 1) {
            columns.add(stripIdentifier(node.getChild(0).getText()));
            return;
        }
        if (".".equals(node.getText()) && node.getChildCount() == 2) {
            String owner = expressionExtractor.unparse((ASTNode) node.getChild(0));
            columns.add(stripIdentifier(owner) + "." + stripIdentifier(node.getChild(1).getText()));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectDirectColumnReferences((ASTNode) node.getChild(i), columns);
        }
    }

    private List<String> directObjectsForColumns(List<String> directColumns, Map<String, String> directAliases) {
        LinkedHashSet<String> objects = new LinkedHashSet<>();
        for (String column : directColumns) {
            int dotIndex = column.indexOf('.');
            if (dotIndex <= 0) {
                continue;
            }
            String owner = column.substring(0, dotIndex).toLowerCase(Locale.ROOT);
            String object = directAliases.get(owner);
            objects.add(object == null ? owner : object);
        }
        return new ArrayList<>(objects);
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

    private static final class SubqueryColumnProjection {
        private final String subqueryAlias;
        private final String columnName;
        private final String expression;
        private final String sourceColumn;

        private SubqueryColumnProjection(String subqueryAlias, String columnName, String expression, String sourceColumn) {
            this.subqueryAlias = subqueryAlias;
            this.columnName = columnName;
            this.expression = expression;
            this.sourceColumn = sourceColumn;
        }

        private String getColumnName() {
            return columnName;
        }

        private String getQualifiedColumnName() {
            return subqueryAlias + "." + columnName;
        }

        private String getExpression() {
            return expression;
        }

        private String getSourceColumn() {
            return sourceColumn;
        }
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
