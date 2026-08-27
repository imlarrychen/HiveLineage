package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

final class HiveAstJoinExtractor {
    private final HiveAstWalker astWalker;
    private final HiveAstExpressionExtractor expressionExtractor;

    HiveAstJoinExtractor(HiveAstWalker astWalker, HiveAstExpressionExtractor expressionExtractor) {
        this.astWalker = astWalker;
        this.expressionExtractor = expressionExtractor;
    }

    List<ParsedJoinCondition> extract(ASTNode root, Set<String> cteAliases) {
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
        NestedJoinTraversalContext context = new NestedJoinTraversalContext(
            cteAliases,
            joinIndex,
            conditions,
            new int[] {1},
            false
        );
        for (int i = 0; i < queryNode.getChildCount(); i++) {
            collectNestedSubqueryJoinConditions(
                (ASTNode) queryNode.getChild(i),
                parentPath,
                context
            );
        }
    }

    private void collectNestedSubqueryJoinConditions(
        ASTNode node,
        String parentPath,
        NestedJoinTraversalContext context
    ) {
        if (node == null) {
            return;
        }
        if (node.getType() == HiveParser.TOK_CTE) {
            return;
        }
        boolean childInWhere = context.isInWhere() || node.getType() == HiveParser.TOK_WHERE;
        if (node.getType() == HiveParser.TOK_QUERY) {
            String queryBlockPath = childInWhere
                ? parentPath + ".where.exists[" + context.nextSubqueryIndex() + "]"
                : parentPath + ".subquery[" + context.nextSubqueryIndex() + "]";
            collectJoinConditionsFromQuery(
                node,
                queryBlockPath,
                context.getCteAliases(),
                context.getJoinIndex(),
                context.getConditions()
            );
            for (int i = 0; i < node.getChildCount(); i++) {
                collectNestedSubqueryJoinConditions(
                    (ASTNode) node.getChild(i),
                    queryBlockPath,
                    context.forChildScope()
                );
            }
            return;
        }
        if (node.getType() == HiveParser.TOK_SUBQUERY) {
            ASTNode queryNode = astWalker.directChild(node, HiveParser.TOK_QUERY);
            if (queryNode != null) {
                String queryBlockPath = childInWhere
                    ? parentPath + ".where.exists[" + context.nextSubqueryIndex() + "]"
                    : parentPath + ".subquery[" + context.nextSubqueryIndex() + "]";
                collectJoinConditionsFromQuery(
                    queryNode,
                    queryBlockPath,
                    context.getCteAliases(),
                    context.getJoinIndex(),
                    context.getConditions()
                );
                collectNestedSubqueryJoinConditions(queryNode, queryBlockPath, context.forChildScope());
                return;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectNestedSubqueryJoinConditions(
                (ASTNode) node.getChild(i),
                parentPath,
                context.withInWhere(childInWhere)
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
        collectImplicitWhereJoinCondition(queryNode, fromNode, queryBlockPath, directAliases, joinIndex, conditions);
    }

    private void collectImplicitWhereJoinCondition(
        ASTNode queryNode,
        ASTNode fromNode,
        String queryBlockPath,
        Map<String, String> directAliases,
        int[] joinIndex,
        List<ParsedJoinCondition> conditions
    ) {
        if (containsJoinConditionNode(fromNode) || topLevelFromSourceCount(fromNode) < 2) {
            return;
        }
        ASTNode whereCondition = directWhereCondition(queryNode);
        if (whereCondition == null) {
            return;
        }
        List<ASTNode> joinConjuncts = new ArrayList<>();
        collectImplicitJoinConjuncts(whereCondition, directAliases, joinConjuncts);
        if (joinConjuncts.isEmpty()) {
            return;
        }
        LinkedHashSet<String> directColumnSet = new LinkedHashSet<>();
        List<String> expressions = new ArrayList<>();
        for (ASTNode joinConjunct : joinConjuncts) {
            expressions.add(expressionExtractor.unparse(joinConjunct));
            directColumnSet.addAll(collectDirectColumnReferences(joinConjunct));
        }
        List<String> directColumns = new ArrayList<>(directColumnSet);
        List<String> directObjects = directObjectsForColumns(directColumns, directAliases);
        conditions.add(new ParsedJoinCondition(
            joinIndex[0]++,
            queryBlockPath,
            7,
            "IMPLICIT_JOIN",
            directObjects,
            directColumns,
            joinWithAnd(expressions)
        ));
    }

    private boolean containsJoinConditionNode(ASTNode node) {
        if (node == null) {
            return false;
        }
        if (isJoinNode(node) && joinConditionNode(node) != null) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsJoinConditionNode((ASTNode) node.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private int topLevelFromSourceCount(ASTNode fromNode) {
        if (fromNode == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < fromNode.getChildCount(); i++) {
            count += sourceLeafCount((ASTNode) fromNode.getChild(i));
        }
        return count;
    }

    private int sourceLeafCount(ASTNode node) {
        if (node == null) {
            return 0;
        }
        if (node.getType() == HiveParser.TOK_TABREF || node.getType() == HiveParser.TOK_SUBQUERY) {
            return 1;
        }
        int count = 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += sourceLeafCount((ASTNode) node.getChild(i));
        }
        return count;
    }

    private ASTNode directWhereCondition(ASTNode queryNode) {
        ASTNode insertNode = astWalker.directChild(queryNode, HiveParser.TOK_INSERT);
        ASTNode whereNode = astWalker.directChild(insertNode, HiveParser.TOK_WHERE);
        if (whereNode == null || whereNode.getChildCount() == 0) {
            return null;
        }
        return (ASTNode) whereNode.getChild(0);
    }

    private void collectImplicitJoinConjuncts(
        ASTNode node,
        Map<String, String> directAliases,
        List<ASTNode> joinConjuncts
    ) {
        if (node == null) {
            return;
        }
        if ("and".equalsIgnoreCase(node.getText()) && node.getChildCount() == 2) {
            collectImplicitJoinConjuncts((ASTNode) node.getChild(0), directAliases, joinConjuncts);
            collectImplicitJoinConjuncts((ASTNode) node.getChild(1), directAliases, joinConjuncts);
            return;
        }
        if (isImplicitJoinExpression(node, directAliases)) {
            joinConjuncts.add(node);
        }
    }

    private boolean isImplicitJoinExpression(ASTNode expressionNode, Map<String, String> directAliases) {
        List<String> directColumns = collectDirectColumnReferences(expressionNode);
        if (directColumns.size() < 2) {
            return false;
        }
        Set<String> owners = new LinkedHashSet<>();
        boolean hasUnqualifiedColumn = false;
        for (String directColumn : directColumns) {
            String owner = HiveSqlTextUtils.tableNameOfColumn(directColumn);
            if (owner == null) {
                hasUnqualifiedColumn = true;
                continue;
            }
            String normalizedOwner = owner.toLowerCase(Locale.ROOT);
            String sourceObject = directAliases.get(normalizedOwner);
            owners.add(sourceObject == null ? normalizedOwner : sourceObject.toLowerCase(Locale.ROOT));
        }
        return owners.size() >= 2 || hasUnqualifiedColumn;
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
        return type == HiveParser.TOK_JOIN || isOuterJoin(type) || isSemiJoin(type);
    }

    private boolean isOuterJoin(int type) {
        return type == HiveParser.TOK_LEFTOUTERJOIN
            || type == HiveParser.TOK_RIGHTOUTERJOIN
            || type == HiveParser.TOK_FULLOUTERJOIN;
    }

    private boolean isSemiJoin(int type) {
        return type == HiveParser.TOK_LEFTSEMIJOIN || type == HiveParser.TOK_LEFTANTISEMIJOIN;
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
            String owner = HiveSqlTextUtils.tableNameOfColumn(column);
            if (owner == null) {
                continue;
            }
            String normalizedOwner = owner.toLowerCase(Locale.ROOT);
            String object = directAliases.get(normalizedOwner);
            objects.add(object == null ? normalizedOwner : object);
        }
        return new ArrayList<>(objects);
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

    private String joinWithAnd(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) {
                builder.append(" and ");
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private static final class NestedJoinTraversalContext {
        private final Set<String> cteAliases;
        private final int[] joinIndex;
        private final List<ParsedJoinCondition> conditions;
        private final int[] subqueryIndex;
        private final boolean inWhere;

        private NestedJoinTraversalContext(
            Set<String> cteAliases,
            int[] joinIndex,
            List<ParsedJoinCondition> conditions,
            int[] subqueryIndex,
            boolean inWhere
        ) {
            this.cteAliases = cteAliases;
            this.joinIndex = joinIndex;
            this.conditions = conditions;
            this.subqueryIndex = subqueryIndex;
            this.inWhere = inWhere;
        }

        private Set<String> getCteAliases() {
            return cteAliases;
        }

        private int[] getJoinIndex() {
            return joinIndex;
        }

        private List<ParsedJoinCondition> getConditions() {
            return conditions;
        }

        private int nextSubqueryIndex() {
            return subqueryIndex[0]++;
        }

        private boolean isInWhere() {
            return inWhere;
        }

        private NestedJoinTraversalContext forChildScope() {
            return new NestedJoinTraversalContext(
                cteAliases,
                joinIndex,
                conditions,
                new int[] {1},
                false
            );
        }

        private NestedJoinTraversalContext withInWhere(boolean childInWhere) {
            return new NestedJoinTraversalContext(
                cteAliases,
                joinIndex,
                conditions,
                subqueryIndex,
                childInWhere
            );
        }
    }
}
