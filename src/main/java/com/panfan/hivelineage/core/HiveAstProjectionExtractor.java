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
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

final class HiveAstProjectionExtractor {
    private final HiveAstWalker astWalker;
    private final HiveAstExpressionExtractor expressionExtractor;

    HiveAstProjectionExtractor(HiveAstWalker astWalker, HiveAstExpressionExtractor expressionExtractor) {
        this.astWalker = astWalker;
        this.expressionExtractor = expressionExtractor;
    }

    List<DataObjectRef> resolveInputObjects(ASTNode root, Set<String> cteAliases) {
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

    List<String> resolveTargetColumns(
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

    List<String> resolveSelectExpressions(ASTNode root, ASTNode mainInsert) {
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

    ASTNode findMainSetOperation(ASTNode root, ASTNode mainInsert) {
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
        if (text != null) {
            String upperText = text.toUpperCase(Locale.ROOT);
            if (upperText.startsWith("TOK_UNION")
                || upperText.startsWith("TOK_INTERSECT")
                || upperText.startsWith("TOK_EXCEPT")) {
                return node;
            }
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

    Map<Integer, List<String>> resolveSetOperationSourceColumns(ASTNode root, Set<String> cteAliases) {
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

    Map<Integer, List<String>> resolveSetOperationExpressions(ASTNode root, Set<String> cteAliases) {
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
            if (selectNode == null) {
                return;
            }
            collectQuerySetOperationExpressions(node, selectNode, cteAliases, collected);
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
            if (selectNode == null) {
                return;
            }
            collectQuerySetOperationSourceColumns(node, selectNode, cteAliases, collected);
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSetOperationSourceColumns((ASTNode) node.getChild(i), cteAliases, collected);
        }
    }

    private void collectQuerySetOperationExpressions(
        ASTNode queryNode,
        ASTNode selectNode,
        Set<String> cteAliases,
        Map<Integer, LinkedHashSet<String>> collected
    ) {
        Map<String, String> branchAliases = resolveSourceAliases(queryNode, cteAliases);
        String defaultSource = defaultSourceTable(queryNode, cteAliases);
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
            addCollectedValue(collected, i, expression);
        }
    }

    private void collectQuerySetOperationSourceColumns(
        ASTNode queryNode,
        ASTNode selectNode,
        Set<String> cteAliases,
        Map<Integer, LinkedHashSet<String>> collected
    ) {
        Map<String, String> branchAliases = resolveSourceAliases(queryNode, cteAliases);
        String defaultSource = defaultSourceTable(queryNode, cteAliases);
        for (int i = 0; i < selectNode.getChildCount(); i++) {
            ASTNode selectExpression = (ASTNode) selectNode.getChild(i);
            if (selectExpression.getType() != HiveParser.TOK_SELEXPR || selectExpression.getChildCount() == 0) {
                continue;
            }
            LinkedHashSet<String> expressionSources = new LinkedHashSet<>();
            collectExpressionSourceColumns((ASTNode) selectExpression.getChild(0), branchAliases, defaultSource, expressionSources);
            addCollectedValues(collected, i, expressionSources);
        }
    }

    private void addCollectedValue(Map<Integer, LinkedHashSet<String>> collected, int position, String value) {
        if (value.isEmpty()) {
            return;
        }
        collectedValues(collected, position).add(value);
    }

    private void addCollectedValues(Map<Integer, LinkedHashSet<String>> collected, int position, Set<String> values) {
        if (values.isEmpty()) {
            return;
        }
        collectedValues(collected, position).addAll(values);
    }

    private LinkedHashSet<String> collectedValues(Map<Integer, LinkedHashSet<String>> collected, int position) {
        LinkedHashSet<String> values = collected.get(position);
        if (values == null) {
            values = new LinkedHashSet<>();
            collected.put(position, values);
        }
        return values;
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
        return isTextOrNumericLiteral(type) || isBooleanLiteral(type) || type == HiveParser.KW_NULL;
    }

    private boolean isTextOrNumericLiteral(int type) {
        return type == HiveParser.StringLiteral || type == HiveParser.Number
            || type == HiveParser.NumberLiteral || type == HiveParser.IntegralLiteral;
    }

    private boolean isBooleanLiteral(int type) {
        return type == HiveParser.KW_TRUE || type == HiveParser.KW_FALSE;
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

    Map<String, String> resolveSourceAliases(ASTNode root, Set<String> cteAliases) {
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

    Map<String, String> resolveStaticPartitionValues(ASTNode mainInsert) {
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

    Map<String, String> resolveColumnAliasSources(ASTNode root, Set<String> cteAliases) {
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

    Map<String, String> resolveColumnAliasExpressions(ASTNode root, Set<String> cteAliases) {
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
        collectIdentifierTexts(node, texts);
        return texts;
    }

    private void collectIdentifierTexts(ASTNode node, List<String> texts) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.Identifier) {
            texts.add(stripIdentifier(node.getText()).toLowerCase(Locale.ROOT));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectIdentifierTexts((ASTNode) node.getChild(i), texts);
        }
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
}
