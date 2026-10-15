package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.BetweenSyntax;
import com.panfan.hivelineage.v2.syntax.CaseBranchSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.InListSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.UnaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.hadoop.hive.ql.parse.ASTNode;

/** Owns syntax-specific CASE, BETWEEN, IN, null predicates and casts. */
final class HiveSpecialFunctionNormalizer {
    private final HiveAstSupport ast;
    private final HiveTypeNormalizer types;
    private final Function<ASTNode, SyntaxExpression> expressions;
    private final BiFunction<ASTNode, String, SyntaxExpression> unsupported;
    private final Map<String, Function<ASTNode, SyntaxExpression>> handlers;

    HiveSpecialFunctionNormalizer(HiveAstSupport ast, HiveTypeNormalizer types,
                                  Function<ASTNode, SyntaxExpression> expressions,
                                  BiFunction<ASTNode, String, SyntaxExpression> unsupported) {
        this.ast = ast;
        this.types = types;
        this.expressions = expressions;
        this.unsupported = unsupported;
        Map<String, Function<ASTNode, SyntaxExpression>> operations = new LinkedHashMap<String, Function<ASTNode, SyntaxExpression>>();
        operations.put("case", node -> caseFunction(node, true));
        operations.put("when", node -> caseFunction(node, false));
        operations.put("between", this::between);
        operations.put("in", this::in);
        operations.put("isnull", node -> nullPredicate(node, false));
        operations.put("isnotnull", node -> nullPredicate(node, true));
        this.handlers = Collections.unmodifiableMap(operations);
    }

    SyntaxExpression normalize(ASTNode node) {
        ASTNode head = ast.at(node, 0);
        String name = HiveAstSupport.unquote(head.getText()).toLowerCase(Locale.ROOT);
        Function<ASTNode, SyntaxExpression> handler = handlers.get(name);
        if (handler != null) {
            return handler.apply(node);
        }
        if (types.isCastType(head) && node.getChildCount() == 2) {
            return new CastSyntax(ast.id(node), ast.ref(node), expressions.apply(ast.at(node, 1)), types.type(head));
        }
        return null;
    }

    private SyntaxExpression caseFunction(ASTNode node, boolean simple) {
        if (node.getChildCount() < (simple ? 4 : 3)) {
            return unsupported.apply(node, "TOK_FUNCTION:" + (simple ? "case" : "when"));
        }
        return caseExpression(node, simple);
    }

    private SyntaxExpression between(ASTNode node) {
        if (!validBetween(node)) {
            return unsupported.apply(node, "TOK_FUNCTION:between");
        }
        boolean negated = "KW_TRUE".equals(ast.kind(ast.at(node, 1)));
        return new BetweenSyntax(ast.id(node), ast.ref(node), expressions.apply(ast.at(node, 2)),
                expressions.apply(ast.at(node, 3)), expressions.apply(ast.at(node, 4)), negated);
    }

    private SyntaxExpression in(ASTNode node) {
        if (node.getChildCount() < 3) {
            return unsupported.apply(node, "TOK_FUNCTION:in");
        }
        SyntaxExpression probe = expressions.apply(ast.at(node, 1));
        List<SyntaxExpression> values = new ArrayList<SyntaxExpression>();
        for (int index = 2; index < node.getChildCount(); index++) {
            ASTNode child = ast.at(node, index);
            if (!"TOK_WINDOWSPEC".equals(ast.kind(child))) {
                values.add(expressions.apply(child));
            }
        }
        return new InListSyntax(ast.id(node), ast.ref(node), probe, values, false);
    }

    private SyntaxExpression nullPredicate(ASTNode node, boolean negated) {
        String name = negated ? "isnotnull" : "isnull";
        if (node.getChildCount() != 2) {
            return unsupported.apply(node, "TOK_FUNCTION:" + name);
        }
        return new UnarySyntax(ast.id(node), ast.ref(node), negated ? UnaryOperatorSyntax.IS_NOT_NULL : UnaryOperatorSyntax.IS_NULL,
                expressions.apply(ast.at(node, 1)));
    }

    private boolean validBetween(ASTNode node) {
        return node.getChildCount() == 5 && ("KW_TRUE".equals(ast.kind(ast.at(node, 1)))
                || "KW_FALSE".equals(ast.kind(ast.at(node, 1))));
    }

    private SyntaxExpression caseExpression(ASTNode node, boolean simple) {
        int start = simple ? 2 : 1;
        SyntaxExpression operand = simple ? expressions.apply(ast.at(node, 1)) : null;
        List<CaseBranchSyntax> branches = new ArrayList<CaseBranchSyntax>();
        int index = start;
        while (index + 1 < node.getChildCount()) {
            branches.add(new CaseBranchSyntax(expressions.apply(ast.at(node, index)), expressions.apply(ast.at(node, index + 1))));
            index += 2;
        }
        SyntaxExpression otherwise = index < node.getChildCount() ? expressions.apply(ast.at(node, index)) : null;
        return new CaseSyntax(ast.id(node), ast.ref(node), operand, branches, otherwise);
    }

}
