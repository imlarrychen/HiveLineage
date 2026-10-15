package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.BetweenSyntax;
import com.panfan.hivelineage.v2.syntax.BinaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CallArgumentsSyntax;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CaseBranchSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.DataTypeSyntax;
import com.panfan.hivelineage.v2.syntax.FieldAccessSyntax;
import com.panfan.hivelineage.v2.syntax.FrameBoundaryKindSyntax;
import com.panfan.hivelineage.v2.syntax.FrameBoundarySyntax;
import com.panfan.hivelineage.v2.syntax.FrameUnitSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InListSyntax;
import com.panfan.hivelineage.v2.syntax.LiteralKind;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;
import com.panfan.hivelineage.v2.syntax.NullOrderingSyntax;
import com.panfan.hivelineage.v2.syntax.QuantifierSyntax;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import com.panfan.hivelineage.v2.syntax.SortDirectionSyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.StarSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryKindSyntax;
import com.panfan.hivelineage.v2.syntax.SubscriptSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxName;
import com.panfan.hivelineage.v2.syntax.TypeFieldSyntax;
import com.panfan.hivelineage.v2.syntax.UnaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;
import com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;
import com.panfan.hivelineage.v2.syntax.WindowFrameSyntax;
import com.panfan.hivelineage.v2.syntax.WindowSpecificationSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.IdentityHashMap;

import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;

final class HiveExpressionNormalizer {
    private static final Map<String, BinaryOperatorSyntax> BINARY = binaryOperators();
    private static final Map<String, LiteralKind> LITERALS = literalKinds();
    private static final Set<String> CAST_TYPES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "TOK_BOOLEAN", "TOK_TINYINT", "TOK_SMALLINT", "TOK_INT", "TOK_BIGINT", "TOK_FLOAT", "TOK_DOUBLE",
            "TOK_DECIMAL", "TOK_STRING", "TOK_CHAR", "TOK_VARCHAR", "TOK_BINARY", "TOK_DATE", "TOK_TIMESTAMP",
            "TOK_TIMESTAMPLOCALTZ", "TOK_INTERVAL_YEAR_MONTH", "TOK_INTERVAL_DAY_TIME", "TOK_LIST", "TOK_MAP",
            "TOK_STRUCT", "TOK_UNIONTYPE")));
    private final HiveAstSupport ast;
    private final HiveQueryNormalizer queries;
    private final Map<ASTNode, SyntaxExpression> cache = new IdentityHashMap<ASTNode, SyntaxExpression>();

    HiveExpressionNormalizer(HiveAstSupport ast, HiveQueryNormalizer queries) {
        this.ast = ast;
        this.queries = queries;
    }

    SyntaxExpression expression(ASTNode node) {
        SyntaxExpression cached = cache.get(node);
        if (cached == null) {
            cached = expressionUncached(node);
            cache.put(node, cached);
        }
        return cached;
    }

    private SyntaxExpression expressionUncached(ASTNode node) {
        String kind = ast.kind(node);
        if (ast.recoveredExpression(node) || kind.startsWith("TOK_PARAMETER")) {
            return new UnknownExpressionSyntax(ast.id(node), ast.ref(node), "RECOVERED_PROJECTION",
                    Collections.<SyntaxExpression>emptyList());
        }
        if ("TOK_STRINGLITERALSEQUENCE".equals(kind)) {
            StringBuilder value = new StringBuilder();
            for (ASTNode part : ast.children(node)) {
                value.append(HiveAstSupport.unquote(part.getText()));
            }
            return new LiteralSyntax(ast.id(node), ast.ref(node), LiteralKind.STRING, ast.source(node), value.toString());
        }
        if ("TOK_TABLE_OR_COL".equals(kind) || "Identifier".equals(kind)) {
            return new NameRefSyntax(ast.id(node), ast.ref(node), ast.name(node));
        }
        if ("TOK_ALLCOLREF".equals(kind) || "TOK_SETCOLREF".equals(kind)) {
            return new StarSyntax(ast.id(node), ast.ref(node), node.getChildCount() == 0 ? null : ast.name(ast.at(node, 0)));
        }
        SyntaxExpression structure = structure(node, kind);
        if (structure != null) {
            return structure;
        }
        SyntaxExpression operator = operator(node, kind);
        if (operator != null) {
            return operator;
        }
        if (literalKind(node) != null) {
            return literal(node);
        }
        return unknown(node, kind, knownChildren(node, 0));
    }

    private SyntaxExpression structure(ASTNode node, String kind) {
        if ("DOT".equals(kind) && node.getChildCount() == 2) {
            return field(node);
        }
        if ("LSQUARE".equals(kind) && node.getChildCount() == 2) {
            return new SubscriptSyntax(ast.id(node), ast.ref(node), expression(ast.at(node, 0)), expression(ast.at(node, 1)));
        }
        if (("TOK_FUNCTION".equals(kind) || "TOK_FUNCTIONDI".equals(kind) || "TOK_FUNCTIONSTAR".equals(kind))
                && node.getChildCount() > 0) {
            return function(node);
        }
        if ("TOK_SUBQUERY_EXPR".equals(kind) && node.getChildCount() >= 2) {
            return subquery(node);
        }
        return null;
    }

    private SyntaxExpression operator(ASTNode node, String kind) {
        if (BINARY.containsKey(kind) && node.getChildCount() == 2) {
            return new BinarySyntax(ast.id(node), ast.ref(node), BINARY.get(kind),
                    expression(ast.at(node, 0)), expression(ast.at(node, 1)));
        }
        if (node.getChildCount() == 1 && ("MINUS".equals(kind) || "PLUS".equals(kind)
                || "KW_NOT".equals(kind) || "TILDE".equals(kind))) {
            UnaryOperatorSyntax operator = "KW_NOT".equals(kind) ? UnaryOperatorSyntax.NOT
                    : "MINUS".equals(kind) ? UnaryOperatorSyntax.MINUS : "TILDE".equals(kind)
                                                                         ? UnaryOperatorSyntax.BITWISE_NOT : UnaryOperatorSyntax.PLUS;
            return new UnarySyntax(ast.id(node), ast.ref(node), operator, expression(ast.at(node, 0)));
        }
        return null;
    }

    private UnknownExpressionSyntax unknown(ASTNode node, String kind, List<SyntaxExpression> children) {
        String reason = "UNSUPPORTED_NATIVE_EXPRESSION:" + kind;
        ast.unsupported(node, reason);
        return new UnknownExpressionSyntax(ast.id(node), ast.ref(node), reason, children);
    }

    private SyntaxExpression field(ASTNode node) {
        SyntaxExpression base = expression(ast.at(node, 0));
        IdentifierPart part = ast.part(ast.at(node, 1));
        if (base instanceof NameRefSyntax) {
            List<IdentifierPart> parts = new ArrayList<IdentifierPart>(((NameRefSyntax) base).getName().getParts());
            parts.add(part);
            return new NameRefSyntax(ast.id(node), ast.ref(node), new SyntaxName(parts));
        }
        return new FieldAccessSyntax(ast.id(node), ast.ref(node), base, part);
    }

    private SyntaxExpression literal(ASTNode node) {
        String spelling = node.getText();
        LiteralKind literalKind = literalKind(node);
        if (node.getChildCount() > 0) {
            spelling = ast.source(node);
        }
        return new LiteralSyntax(ast.id(node), ast.ref(node), literalKind, spelling,
                literalKind == LiteralKind.NULL ? null : HiveAstSupport.unquote(spelling));
    }

    private LiteralKind literalKind(ASTNode node) {
        switch (node.getType()) {
            case HiveParser.TOK_DATELITERAL:
                return LiteralKind.DATE;
            case HiveParser.TOK_TIMESTAMPLITERAL:
            case HiveParser.TOK_TIMESTAMPLOCALTZLITERAL:
                return LiteralKind.TIMESTAMP;
            case HiveParser.TOK_INTERVAL_YEAR_MONTH_LITERAL:
            case HiveParser.TOK_INTERVAL_DAY_TIME_LITERAL:
            case HiveParser.TOK_INTERVAL_YEAR_LITERAL:
            case HiveParser.TOK_INTERVAL_MONTH_LITERAL:
            case HiveParser.TOK_INTERVAL_DAY_LITERAL:
            case HiveParser.TOK_INTERVAL_HOUR_LITERAL:
            case HiveParser.TOK_INTERVAL_MINUTE_LITERAL:
            case HiveParser.TOK_INTERVAL_SECOND_LITERAL:
                return LiteralKind.INTERVAL;
            default:
                return LITERALS.get(ast.kind(node));
        }
    }

    private SyntaxExpression function(ASTNode node) {
        ASTNode nameNode = ast.at(node, 0);
        String name = HiveAstSupport.unquote(nameNode.getText()).toLowerCase(Locale.ROOT);
        if ("when".equals(name) || "case".equals(name)) {
            if (node.getChildCount() < ("case".equals(name) ? 4 : 3)) {
                return unknown(node, "TOK_FUNCTION:" + name, knownChildren(node, 1));
            }
            return caseExpression(node, "case".equals(name));
        }
        if ("between".equals(name)) {
            if (!validBetween(node)) {
                return unknown(node, "TOK_FUNCTION:between", knownChildren(node, 1));
            }
            boolean negated = "KW_TRUE".equals(ast.kind(ast.at(node, 1)));
            return new BetweenSyntax(ast.id(node), ast.ref(node), expression(ast.at(node, 2)),
                    expression(ast.at(node, 3)), expression(ast.at(node, 4)), negated);
        }
        if ("in".equals(name)) {
            if (node.getChildCount() < 3) {
                return unknown(node, "TOK_FUNCTION:in", knownChildren(node, 1));
            }
            return new InListSyntax(ast.id(node), ast.ref(node), expression(ast.at(node, 1)), expressions(node, 2), false);
        }
        if ("isnull".equals(name) || "isnotnull".equals(name)) {
            if (node.getChildCount() != 2) {
                return unknown(node, "TOK_FUNCTION:" + name, knownChildren(node, 1));
            }
            return new UnarySyntax(ast.id(node), ast.ref(node), "isnull".equals(name)
                    ? UnaryOperatorSyntax.IS_NULL : UnaryOperatorSyntax.IS_NOT_NULL, expression(ast.at(node, 1)));
        }
        if (CAST_TYPES.contains(ast.kind(nameNode)) && node.getChildCount() == 2) {
            return new CastSyntax(ast.id(node), ast.ref(node), expression(ast.at(node, 1)), type(nameNode));
        }
        if (ast.kind(nameNode).startsWith("TOK_")) {
            return unknown(node, "TOK_FUNCTION:" + ast.kind(nameNode), knownChildren(node, 1));
        }
        List<SyntaxExpression> arguments = expressions(node, 1);
        if ("TOK_FUNCTIONSTAR".equals(ast.kind(node))) {
            arguments.add(new StarSyntax(ast.id(node, "star"), ast.ref(node), null));
        }
        QuantifierSyntax quantifier = "TOK_FUNCTIONDI".equals(ast.kind(node)) ? QuantifierSyntax.DISTINCT : QuantifierSyntax.DEFAULT;
        CallSyntax call = new CallSyntax(ast.id(node), ast.ref(node), ast.name(nameNode),
                new CallArgumentsSyntax(arguments, quantifier, null));
        ASTNode window = ast.child(node, "TOK_WINDOWSPEC");
        return window == null ? call : new WindowCallSyntax(ast.id(node, "window"), ast.ref(node), call, window(window));
    }

    private boolean validBetween(ASTNode node) {
        return node.getChildCount() == 5 && ("KW_TRUE".equals(ast.kind(ast.at(node, 1)))
                || "KW_FALSE".equals(ast.kind(ast.at(node, 1))));
    }

    private List<SyntaxExpression> knownChildren(ASTNode parent, int start) {
        List<SyntaxExpression> children = new ArrayList<SyntaxExpression>();
        for (int index = start; index < parent.getChildCount(); index++) {
            ASTNode child = ast.at(parent, index);
            if ("TOK_WINDOWSPEC".equals(ast.kind(child))) {
                WindowSpecificationSyntax specification = window(child);
                children.addAll(specification.getPartitionBy());
                for (SortItemSyntax sort : specification.getOrderBy()) {
                    children.add(sort.getExpression());
                }
                if (specification.getFrame() != null) {
                    addOffset(children, specification.getFrame().getStart());
                    addOffset(children, specification.getFrame().getEnd());
                }
            } else {
                children.add(expression(child));
            }
        }
        return children;
    }

    private void addOffset(List<SyntaxExpression> children, FrameBoundarySyntax boundary) {
        if (boundary != null && boundary.getOffset() != null) {
            children.add(boundary.getOffset());
        }
    }

    List<SyntaxExpression> expressions(ASTNode parent, int start) {
        List<SyntaxExpression> expressions = new ArrayList<SyntaxExpression>();
        for (int index = start; index < parent.getChildCount(); index++) {
            ASTNode child = ast.at(parent, index);
            if (!"TOK_WINDOWSPEC".equals(ast.kind(child))) {
                expressions.add(expression(child));
            }
        }
        return expressions;
    }

    private SyntaxExpression caseExpression(ASTNode node, boolean simple) {
        int start = simple ? 2 : 1;
        SyntaxExpression operand = simple ? expression(ast.at(node, 1)) : null;
        List<CaseBranchSyntax> branches = new ArrayList<CaseBranchSyntax>();
        int index = start;
        while (index + 1 < node.getChildCount()) {
            branches.add(new CaseBranchSyntax(expression(ast.at(node, index)), expression(ast.at(node, index + 1))));
            index += 2;
        }
        SyntaxExpression otherwise = index < node.getChildCount() ? expression(ast.at(node, index)) : null;
        return new CaseSyntax(ast.id(node), ast.ref(node), operand, branches, otherwise);
    }

    private SyntaxExpression subquery(ASTNode node) {
        ASTNode operation = ast.at(node, 0);
        String operator = operation.getChildCount() == 0 ? ""
                : ast.at(operation, 0).getText().toLowerCase(Locale.ROOT);
        SubqueryKindSyntax kind = "exists".equals(operator) ? SubqueryKindSyntax.EXISTS
                : "in".equals(operator) ? SubqueryKindSyntax.IN : SubqueryKindSyntax.SCALAR;
        SyntaxExpression probe = node.getChildCount() > 2 ? expression(ast.at(node, 2)) : null;
        return new SubqueryExpressionSyntax(ast.id(node), ast.ref(node), kind, queries.query(ast.at(node, 1)), probe);
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

    WindowSpecificationSyntax window(ASTNode node) {
        ASTNode partition = ast.child(node, "TOK_PARTITIONINGSPEC");
        ASTNode partitionBy = ast.child(partition, "TOK_DISTRIBUTEBY");
        ASTNode orderBy = ast.child(partition, "TOK_ORDERBY");
        ASTNode frameNode = ast.child(node, "TOK_WINDOWRANGE");
        if (frameNode == null) {
            frameNode = ast.child(node, "TOK_WINDOWVALUES");
        }
        WindowFrameSyntax frame = frameNode == null ? null : new WindowFrameSyntax(
                "TOK_WINDOWRANGE".equals(ast.kind(frameNode)) ? FrameUnitSyntax.ROWS : FrameUnitSyntax.RANGE,
                boundary(ast.at(frameNode, 0)), frameNode.getChildCount() > 1 ? boundary(ast.at(frameNode, 1)) : null);
        IdentifierPart base = null;
        for (ASTNode child : ast.children(node)) {
            if ("Identifier".equals(ast.kind(child))) {
                base = ast.part(child);
            }
        }
        return new WindowSpecificationSyntax(base, partitionBy == null ? Collections.<SyntaxExpression>emptyList()
                : expressions(partitionBy, 0), sorts(orderBy), frame);
    }

    private FrameBoundarySyntax boundary(ASTNode node) {
        String direction = node.getText().toLowerCase(Locale.ROOT);
        boolean preceding = "preceding".equals(direction);
        if ("current".equals(direction)) {
            return new FrameBoundarySyntax(FrameBoundaryKindSyntax.CURRENT_ROW, null);
        }
        ASTNode offset = ast.at(node, 0);
        if ("unbounded".equalsIgnoreCase(offset.getText())) {
            return new FrameBoundarySyntax(preceding ? FrameBoundaryKindSyntax.UNBOUNDED_PRECEDING
                    : FrameBoundaryKindSyntax.UNBOUNDED_FOLLOWING, null);
        }
        return new FrameBoundarySyntax(preceding ? FrameBoundaryKindSyntax.PRECEDING
                : FrameBoundaryKindSyntax.FOLLOWING, expression(offset));
    }

    List<SortItemSyntax> sorts(ASTNode node) {
        List<SortItemSyntax> items = new ArrayList<SortItemSyntax>();
        for (ASTNode item : ast.children(node)) {
            SortDirectionSyntax direction = ast.kind(item).endsWith("DESC") ? SortDirectionSyntax.DESC : SortDirectionSyntax.ASC;
            ASTNode value = ast.at(item, 0);
            NullOrderingSyntax nullOrder = NullOrderingSyntax.DEFAULT;
            if (ast.kind(value).startsWith("TOK_NULLS_")) {
                nullOrder = ast.kind(value).endsWith("FIRST") ? NullOrderingSyntax.FIRST : NullOrderingSyntax.LAST;
                value = ast.at(value, 0);
            }
            items.add(new SortItemSyntax(expression(value), direction, nullOrder));
        }
        return items;
    }

    private static Map<String, BinaryOperatorSyntax> binaryOperators() {
        Map<String, BinaryOperatorSyntax> operators = new LinkedHashMap<String, BinaryOperatorSyntax>();
        String[] names = {"PLUS", "MINUS", "STAR", "DIVIDE", "DIV", "MOD", "EQUAL", "EQUAL_NS", "NOTEQUAL",
                "LESSTHAN", "LESSTHANOREQUALTO", "GREATERTHAN", "GREATERTHANOREQUALTO", "KW_AND", "KW_OR",
                "AMPERSAND", "BITWISEOR", "BITWISEXOR", "KW_LIKE", "KW_RLIKE", "KW_REGEXP"};
        BinaryOperatorSyntax[] values = {BinaryOperatorSyntax.ADD, BinaryOperatorSyntax.SUBTRACT,
                BinaryOperatorSyntax.MULTIPLY, BinaryOperatorSyntax.DIVIDE, BinaryOperatorSyntax.DIVIDE,
                BinaryOperatorSyntax.MODULO, BinaryOperatorSyntax.EQUAL, BinaryOperatorSyntax.NULL_SAFE_EQUAL,
                BinaryOperatorSyntax.NOT_EQUAL, BinaryOperatorSyntax.LESS_THAN, BinaryOperatorSyntax.LESS_OR_EQUAL,
                BinaryOperatorSyntax.GREATER_THAN, BinaryOperatorSyntax.GREATER_OR_EQUAL, BinaryOperatorSyntax.AND,
                BinaryOperatorSyntax.OR, BinaryOperatorSyntax.BITWISE_AND, BinaryOperatorSyntax.BITWISE_OR,
                BinaryOperatorSyntax.BITWISE_XOR, BinaryOperatorSyntax.LIKE, BinaryOperatorSyntax.RLIKE, BinaryOperatorSyntax.RLIKE};
        for (int index = 0; index < names.length; index++) {
            operators.put(names[index], values[index]);
        }
        return Collections.unmodifiableMap(operators);
    }

    private static Map<String, LiteralKind> literalKinds() {
        Map<String, LiteralKind> kinds = new LinkedHashMap<String, LiteralKind>();
        for (String kind : Arrays.asList("Number", "NumberLiteral", "IntegralLiteral", "ByteLengthLiteral")) {
            kinds.put(kind, LiteralKind.NUMBER);
        }
        kinds.put("StringLiteral", LiteralKind.STRING);
        kinds.put("TOK_NULL", LiteralKind.NULL);
        kinds.put("KW_TRUE", LiteralKind.BOOLEAN);
        kinds.put("KW_FALSE", LiteralKind.BOOLEAN);
        kinds.put("TOK_DATELITERAL", LiteralKind.DATE);
        kinds.put("TOK_TIMESTAMPLITERAL", LiteralKind.TIMESTAMP);
        kinds.put("TOK_TIMESTAMPLOCALTZLITERAL", LiteralKind.TIMESTAMP);
        for (String kind : Arrays.asList("TOK_INTERVAL_YEAR_MONTH_LITERAL", "TOK_INTERVAL_DAY_TIME_LITERAL",
                "TOK_INTERVAL_YEAR_LITERAL", "TOK_INTERVAL_MONTH_LITERAL", "TOK_INTERVAL_DAY_LITERAL",
                "TOK_INTERVAL_HOUR_LITERAL", "TOK_INTERVAL_MINUTE_LITERAL", "TOK_INTERVAL_SECOND_LITERAL")) {
            kinds.put(kind, LiteralKind.INTERVAL);
        }
        return Collections.unmodifiableMap(kinds);
    }
}
