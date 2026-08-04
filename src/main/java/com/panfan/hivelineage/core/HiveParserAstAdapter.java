package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final HiveAstJoinExtractor joinExtractor = new HiveAstJoinExtractor(astWalker, expressionExtractor);
    private final HiveAstProjectionExtractor projectionExtractor = new HiveAstProjectionExtractor(astWalker, expressionExtractor);

    @Override
    public ParsedHiveStatement parse(String normalizedSql) {
        try {
            String parserSql = sqlForHiveParser(normalizedSql);
            ASTNode root = parseDriver.parse(parserSql).getTree();
            StatementType statementType = resolveStatementType(root);
            Set<String> cteAliases = collectCteAliases(root);
            ASTNode mainInsert = findMainInsert(root, statementType);
            ASTNode mainSetOperation = projectionExtractor.findMainSetOperation(root, mainInsert);
            List<String> selectExpressions = projectionExtractor.resolveSelectExpressions(root, mainInsert);
            List<String> targetColumns = projectionExtractor.resolveTargetColumns(root, mainInsert, statementType, selectExpressions);
            if (targetColumns.isEmpty() && statementType == StatementType.INSERT_SELECT) {
                targetColumns = resolveInsertTargetColumns(normalizedSql);
            }
            return new ParsedHiveStatement(
                statementType,
                resolveOutputObject(root, mainInsert, statementType),
                projectionExtractor.resolveInputObjects(root, cteAliases),
                targetColumns,
                selectExpressions,
                projectionExtractor.resolveSourceAliases(root, cteAliases),
                projectionExtractor.resolveColumnAliasSources(root, cteAliases),
                projectionExtractor.resolveColumnAliasExpressions(root, cteAliases),
                projectionExtractor.resolveSetOperationSourceColumns(mainSetOperation, cteAliases),
                projectionExtractor.resolveSetOperationExpressions(mainSetOperation, cteAliases),
                projectionExtractor.resolveStaticPartitionValues(mainInsert),
                joinExtractor.extract(root, cteAliases),
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
}
