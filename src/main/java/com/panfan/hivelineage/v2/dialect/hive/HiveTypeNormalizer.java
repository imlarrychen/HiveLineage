package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.DataTypeSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.TypeFieldSyntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.hadoop.hive.ql.parse.ASTNode;

/** Owns native type declarations and the set of AST heads accepted as casts. */
final class HiveTypeNormalizer {
    private static final Set<String> CAST_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "TOK_BOOLEAN", "TOK_TINYINT", "TOK_SMALLINT", "TOK_INT", "TOK_BIGINT", "TOK_FLOAT", "TOK_DOUBLE",
            "TOK_DECIMAL", "TOK_STRING", "TOK_CHAR", "TOK_VARCHAR", "TOK_BINARY", "TOK_DATE", "TOK_TIMESTAMP",
            "TOK_TIMESTAMPLOCALTZ", "TOK_INTERVAL_YEAR_MONTH", "TOK_INTERVAL_DAY_TIME", "TOK_LIST", "TOK_MAP",
            "TOK_STRUCT", "TOK_UNIONTYPE")));
    private final HiveAstSupport ast;

    HiveTypeNormalizer(HiveAstSupport ast) {
        this.ast = ast;
    }

    boolean isCastType(ASTNode node) {
        return CAST_TYPES.contains(ast.kind(node));
    }

    DataTypeSyntax type(ASTNode node) {
        List<DataTypeSyntax> arguments = new ArrayList<DataTypeSyntax>();
        List<Integer> numeric = new ArrayList<Integer>();
        List<TypeFieldSyntax> fields = new ArrayList<TypeFieldSyntax>();
        for (ASTNode child : ast.children(node)) {
            if ("TOK_TABCOLLIST".equals(ast.kind(child))) {
                for (ASTNode field : ast.children(child)) {
                    fields.add(new TypeFieldSyntax(ast.part(ast.at(field, 0)), type(ast.at(field, 1))));
                }
            } else if ("Number".equals(ast.kind(child))) {
                numeric.add(Integer.valueOf(child.getText()));
            } else {
                arguments.add(type(child));
            }
        }
        String typeName = ast.kind(node).startsWith("TOK_") ? ast.kind(node).substring(4).toLowerCase(Locale.ROOT) : node.getText();
        IdentifierPart part = new IdentifierPart(node.getText(), typeName, QuoteKind.NONE, ast.ref(node));
        return new DataTypeSyntax(new SyntaxName(Collections.singletonList(part)), arguments, numeric, fields);
    }

}
