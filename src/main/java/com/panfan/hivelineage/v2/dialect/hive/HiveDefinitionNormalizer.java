package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.AlterActionSyntax;
import com.panfan.hivelineage.v2.syntax.ChangeColumnActionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnPositionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnChangeKindSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnDefinitionSyntax;
import com.panfan.hivelineage.v2.syntax.ColumnsActionSyntax;
import com.panfan.hivelineage.v2.syntax.DataTypeSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionBodySyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionOptionsSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionSchemaSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DelimitedRowFormatSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.PropertiesActionSyntax;
import com.panfan.hivelineage.v2.syntax.RenameActionSyntax;
import com.panfan.hivelineage.v2.syntax.SerdeSyntax;
import com.panfan.hivelineage.v2.syntax.SetLocationActionSyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.StorageSpecificationSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.UnknownAlterActionSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hive.ql.parse.ASTNode;

final class HiveDefinitionNormalizer {
    private final HiveAstSupport ast;
    private final HiveQueryNormalizer queries;

    HiveDefinitionNormalizer(HiveAstSupport ast, HiveQueryNormalizer queries) {
        this.ast = ast;
        this.queries = queries;
    }

    DefinitionStatementSyntax definition(ASTNode node) {
        String kind = ast.kind(node);
        DefinitionKindSyntax definitionKind = kind(kind);
        ASTNode query = ast.first(node, "TOK_QUERY");
        ASTNode columns = ast.child(node, "TOK_TABCOLLIST");
        if (columns == null) {
            columns = ast.child(node, "TOK_TABCOLNAME");
        }
        DefinitionSchemaSyntax schema = new DefinitionSchemaSyntax(columns(columns),
                columns(ast.child(node, "TOK_TABLEPARTCOLS")), storage(node), like(node));
        DefinitionOptionsSyntax options = new DefinitionOptionsSyntax(ast.child(node, "KW_TEMPORARY") != null,
                ast.child(node, "KW_EXTERNAL") != null, ast.child(node, "TOK_IFNOTEXISTS") != null,
                ast.child(node, "TOK_IFEXISTS") != null, properties(ast.child(node, "TOK_TABLEPROPERTIES")),
                location(ast.child(node, "TOK_TABLELOCATION")), ast.child(node, "TOK_ORREPLACE") != null,
                comment(ast.child(node, "TOK_TABLECOMMENT")));
        return new DefinitionStatementSyntax(ast.id(node, "statement"), ast.ref(node), new DefinitionBodySyntax(definitionKind,
                ast.name(ast.first(node, "TOK_TABNAME")), schema, options, query == null ? null : queries.query(query), actions(node)));
    }

    private DefinitionKindSyntax kind(String kind) {
        if ("TOK_CREATETABLE".equals(kind)) {
            return DefinitionKindSyntax.CREATE_TABLE;
        }
        if ("TOK_CREATEVIEW".equals(kind)) {
            return DefinitionKindSyntax.CREATE_VIEW;
        }
        if ("TOK_ALTERVIEW".equals(kind)) {
            return DefinitionKindSyntax.ALTER_VIEW;
        }
        if ("TOK_DROPTABLE".equals(kind)) {
            return DefinitionKindSyntax.DROP_TABLE;
        }
        if ("TOK_DROPVIEW".equals(kind)) {
            return DefinitionKindSyntax.DROP_VIEW;
        }
        return "TOK_TRUNCATETABLE".equals(kind) ? DefinitionKindSyntax.TRUNCATE_TABLE : DefinitionKindSyntax.ALTER_TABLE;
    }

    private List<ColumnDefinitionSyntax> columns(ASTNode node) {
        List<ColumnDefinitionSyntax> columns = new ArrayList<ColumnDefinitionSyntax>();
        for (ASTNode column : ast.children(node)) {
            if ("TOK_TABCOL".equals(ast.kind(column))) {
                DataTypeSyntax type = column.getChildCount() > 1 && !"TOK_NULL".equals(ast.kind(ast.at(column, 1)))
                        ? queries.expressions().type(ast.at(column, 1)) : null;
                columns.add(new ColumnDefinitionSyntax(ast.part(ast.at(column, 0)), type, comment(column)));
            } else {
                columns.add(new ColumnDefinitionSyntax(ast.part(column), null, null));
            }
        }
        return columns;
    }

    private SyntaxName like(ASTNode node) {
        ASTNode like = ast.child(node, "TOK_LIKETABLE");
        return like == null || like.getChildCount() == 0 ? null : ast.name(ast.at(like, 0));
    }

    private SyntaxExpression location(ASTNode node) {
        return node == null || node.getChildCount() == 0 ? null : queries.expressions().expression(ast.at(node, 0));
    }

    private String comment(ASTNode node) {
        for (ASTNode child : ast.children(node)) {
            if (ast.kind(child).contains("StringLiteral")) {
                return HiveAstSupport.unquote(child.getText());
            }
        }
        return null;
    }

    private StorageSpecificationSyntax storage(ASTNode node) {
        ASTNode format = ast.child(node, "TOK_FILEFORMAT_GENERIC");
        SyntaxName fileFormat = format == null ? null : ast.name(ast.at(format, 0));
        ASTNode serdeNode = ast.first(node, "TOK_TABLESERIALIZER");
        ASTNode serdeName = ast.child(serdeNode, "TOK_SERDENAME");
        ASTNode serdeBody = serdeName == null ? serdeNode : serdeName;
        SerdeSyntax serde = serdeBody == null ? null : new SerdeSyntax(
                HiveAstSupport.unquote(ast.at(serdeBody, 0).getText()), properties(ast.child(serdeBody, "TOK_TABLEPROPERTIES")));
        ASTNode bucket = ast.child(node, "TOK_TABLEBUCKETS");
        if (bucket == null) {
            bucket = ast.child(node, "TOK_ALTERTABLE_BUCKETS");
        }
        List<IdentifierPart> buckets = new ArrayList<IdentifierPart>();
        List<SortItemSyntax> sorts = Collections.emptyList();
        SyntaxExpression count = null;
        if (bucket != null) {
            for (ASTNode column : ast.children(ast.at(bucket, 0))) {
                buckets.add(ast.part(column));
            }
            count = queries.expressions().expression(ast.at(bucket, bucket.getChildCount() - 1));
            if (bucket.getChildCount() > 2) {
                sorts = queries.expressions().sorts(ast.at(bucket, 1));
            }
        }
        return new StorageSpecificationSyntax(fileFormat, serde, rowFormat(ast.child(node, "TOK_TABLEROWFORMAT")), buckets, sorts, count);
    }

    private DelimitedRowFormatSyntax rowFormat(ASTNode node) {
        if (node == null) {
            return null;
        }
        ASTNode properties = ast.child(node, "TOK_SERDEPROPS");
        if (properties != null) {
            node = properties;
        }
        return new DelimitedRowFormatSyntax(delimiter(node, "TOK_TABLEROWFORMATFIELD", 0),
                delimiter(node, "TOK_TABLEROWFORMATFIELD", 1), delimiter(node, "TOK_TABLEROWFORMATCOLLITEMS", 0),
                delimiter(node, "TOK_TABLEROWFORMATMAPKEYS", 0), delimiter(node, "TOK_TABLEROWFORMATLINES", 0),
                delimiter(node, "TOK_TABLEROWFORMATNULL", 0));
    }

    private String delimiter(ASTNode node, String kind, int index) {
        ASTNode child = ast.child(node, kind);
        return child == null || child.getChildCount() <= index ? null : HiveAstSupport.unquote(ast.at(child, index).getText());
    }

    private Map<String, String> properties(ASTNode node) {
        Map<String, String> properties = new LinkedHashMap<String, String>();
        if (node == null) {
            return properties;
        }
        ASTNode entries = ast.child(node, "TOK_TABLEPROPLIST");
        for (ASTNode entry : ast.children(entries == null ? node : entries)) {
            if (entry.getChildCount() >= 2) {
                properties.put(HiveAstSupport.unquote(ast.at(entry, 0).getText()), HiveAstSupport.unquote(ast.at(entry, 1).getText()));
            }
        }
        return properties;
    }

    private List<AlterActionSyntax> actions(ASTNode node) {
        List<AlterActionSyntax> actions = new ArrayList<AlterActionSyntax>();
        boolean partitionScoped = ast.child(node, "TOK_PARTSPEC") != null;
        for (ASTNode action : ast.children(node)) {
            String kind = ast.kind(action);
            if (!kind.startsWith("TOK_ALTER")) {
                continue;
            }
            // Partition schemas may differ from the table schema. Do not apply their changes to the table.
            if (partitionScoped) {
                actions.add(new UnknownAlterActionSyntax(kind));
            } else if ("TOK_ALTERTABLE_RENAME".equals(kind) || "TOK_ALTERVIEW_RENAME".equals(kind)) {
                actions.add(new RenameActionSyntax(ast.name(ast.at(action, 0))));
            } else if ("TOK_ALTERTABLE_ADDCOLS".equals(kind) || "TOK_ALTERTABLE_REPLACECOLS".equals(kind)) {
                actions.add(new ColumnsActionSyntax(kind.endsWith("ADDCOLS") ? ColumnChangeKindSyntax.ADD
                        : ColumnChangeKindSyntax.REPLACE, columns(ast.at(action, 0))));
            } else if ("TOK_ALTERTABLE_RENAMECOL".equals(kind)) {
                ASTNode after = ast.child(action, "TOK_ALTERTABLE_CHANGECOL_AFTER_POSITION");
                ColumnPositionSyntax position = after != null
                        ? new ColumnPositionSyntax(ColumnPositionKindSyntax.AFTER, ast.part(ast.at(after, 0)))
                        : new ColumnPositionSyntax(ast.child(action, "KW_FIRST") != null
                                ? ColumnPositionKindSyntax.FIRST : ColumnPositionKindSyntax.UNCHANGED, null);
                actions.add(new ChangeColumnActionSyntax(ast.part(ast.at(action, 0)),
                        new ColumnDefinitionSyntax(ast.part(ast.at(action, 1)),
                                queries.expressions().type(ast.at(action, 2)), comment(action)), position));
            } else if ("TOK_ALTERTABLE_PROPERTIES".equals(kind)) {
                actions.add(new PropertiesActionSyntax(properties(ast.at(action, 0))));
            } else if ("TOK_ALTERTABLE_LOCATION".equals(kind)) {
                actions.add(new SetLocationActionSyntax(queries.expressions().expression(ast.at(action, 0))));
            } else {
                actions.add(new UnknownAlterActionSyntax(kind));
            }
        }
        if (("TOK_ALTERTABLE".equals(ast.kind(node)) || "TOK_ALTERVIEW".equals(ast.kind(node))) && actions.isEmpty()) {
            actions.add(new UnknownAlterActionSyntax(ast.kind(node)));
        }
        return actions;
    }
}
