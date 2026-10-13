package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CommaFromSyntax;
import com.panfan.hivelineage.v2.syntax.CteSyntax;
import com.panfan.hivelineage.v2.syntax.DerivedFromSyntax;
import com.panfan.hivelineage.v2.syntax.FromSyntax;
import com.panfan.hivelineage.v2.syntax.GenerateSyntax;
import com.panfan.hivelineage.v2.syntax.GroupingKindSyntax;
import com.panfan.hivelineage.v2.syntax.GroupingSyntax;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.JoinConditionKindSyntax;
import com.panfan.hivelineage.v2.syntax.JoinConditionSyntax;
import com.panfan.hivelineage.v2.syntax.JoinFromSyntax;
import com.panfan.hivelineage.v2.syntax.JoinOperandsSyntax;
import com.panfan.hivelineage.v2.syntax.JoinTypeSyntax;
import com.panfan.hivelineage.v2.syntax.LateralFromSyntax;
import com.panfan.hivelineage.v2.syntax.LimitSyntax;
import com.panfan.hivelineage.v2.syntax.NamedFromSyntax;
import com.panfan.hivelineage.v2.syntax.NamedWindowSyntax;
import com.panfan.hivelineage.v2.syntax.QuantifierSyntax;
import com.panfan.hivelineage.v2.syntax.QueryClausesSyntax;
import com.panfan.hivelineage.v2.syntax.QueryTailSyntax;
import com.panfan.hivelineage.v2.syntax.SampleKindSyntax;
import com.panfan.hivelineage.v2.syntax.SelectBodySyntax;
import com.panfan.hivelineage.v2.syntax.SelectItemSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SetOperationBodySyntax;
import com.panfan.hivelineage.v2.syntax.SetOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.SetQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;
import com.panfan.hivelineage.v2.syntax.TableSampleSyntax;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.hadoop.hive.ql.parse.ASTNode;

final class HiveQueryNormalizer {
    private final HiveAstSupport ast;
    private final HiveExpressionNormalizer expressions;

    HiveQueryNormalizer(HiveAstSupport ast) {
        this.ast = ast;
        this.expressions = new HiveExpressionNormalizer(ast, this);
    }

    HiveExpressionNormalizer expressions() {
        return expressions;
    }

    SyntaxQuery query(ASTNode node) {
        if (isSet(ast.kind(node))) {
            List<SyntaxQuery> branches = new ArrayList<SyntaxQuery>();
            for (ASTNode branch : ast.children(node)) {
                branches.add(query(branch));
            }
            String kind = ast.kind(node);
            SetOperatorSyntax operator = kind.startsWith("TOK_UNION") ? SetOperatorSyntax.UNION
                    : kind.startsWith("TOK_INTERSECT") ? SetOperatorSyntax.INTERSECT : SetOperatorSyntax.EXCEPT;
            return new SetQuerySyntax(ast.id(node, "query"), ast.ref(node), ast.block(node), Collections.<CteSyntax>emptyList(),
                    new SetOperationBodySyntax(operator, kind.endsWith("ALL") && ast.betweenHas(ast.at(node, 0), ast.at(node, 1), "KW_ALL")
                            ? QuantifierSyntax.ALL : QuantifierSyntax.DISTINCT,
                            branches, emptyTail()));
        }
        if (!"TOK_QUERY".equals(ast.kind(node))) {
            throw new IllegalArgumentException("Expected query structure, found " + ast.kind(node));
        }
        ASTNode insert = ast.child(node, "TOK_INSERT");
        if (insert == null) {
            throw new IllegalArgumentException("Query has no projection");
        }
        return select(node, insert);
    }

    SelectQuerySyntax select(ASTNode queryNode, ASTNode insert) {
        ASTNode from = ast.child(queryNode, "TOK_FROM");
        FromSyntax fromSyntax = from == null ? null : from(ast.at(from, 0));
        ASTNode select = ast.child(insert, "TOK_SELECT");
        QuantifierSyntax quantifier = QuantifierSyntax.DEFAULT;
        if (select == null) {
            select = ast.child(insert, "TOK_SELECTDI");
            quantifier = QuantifierSyntax.DISTINCT;
        }
        List<SelectItemSyntax> items = new ArrayList<SelectItemSyntax>();
        for (ASTNode item : ast.children(select)) {
            if (!"TOK_SELEXPR".equals(ast.kind(item))) {
                continue;
            }
            items.add(new SelectItemSyntax(expressions.expression(ast.at(item, 0)),
                    item.getChildCount() > 1 ? ast.part(ast.at(item, 1)) : null));
        }
        return new SelectQuerySyntax(ast.id(insert, "query"), ast.ref(queryNode), ast.block(queryNode), ctes(queryNode),
                new SelectBodySyntax(quantifier, items, fromSyntax, clauses(insert)));
    }

    List<CteSyntax> ctes(ASTNode node) {
        List<CteSyntax> ctes = new ArrayList<CteSyntax>();
        for (ASTNode definition : ast.children(ast.child(node, "TOK_CTE"))) {
            SyntaxQuery query = query(ast.at(definition, 0));
            IdentifierPart name = ast.part(ast.at(definition, 1));
            List<IdentifierPart> columns = ast.cteColumns(ast.at(definition, 1));
            if (definition.getChildCount() > 2) {
                for (ASTNode column : ast.children(ast.at(definition, 2))) {
                    columns.add(ast.part(column));
                }
            }
            ctes.add(new CteSyntax(ast.id(definition), ast.ref(definition), name, columns, query));
        }
        return ctes;
    }

    private QueryClausesSyntax clauses(ASTNode node) {
        List<NamedWindowSyntax> windows = new ArrayList<NamedWindowSyntax>();
        for (ASTNode named : ast.children(ast.child(node, "TOK_WINDOW"))) {
            windows.add(new NamedWindowSyntax(ast.part(ast.at(named, 0)), expressions.window(ast.at(named, 1))));
        }
        return new QueryClausesSyntax(predicate(node, "TOK_WHERE"), grouping(node), predicate(node, "TOK_HAVING"), windows,
                new QueryTailSyntax(expressions.sorts(ast.child(node, "TOK_ORDERBY")),
                        expressionList(node, "TOK_DISTRIBUTEBY"), expressions.sorts(ast.child(node, "TOK_SORTBY")),
                        expressionList(node, "TOK_CLUSTERBY"), limit(ast.child(node, "TOK_LIMIT"))));
    }

    private SyntaxExpression predicate(ASTNode node, String kind) {
        ASTNode predicate = ast.child(node, kind);
        return predicate == null ? null : expressions.expression(ast.at(predicate, 0));
    }

    private List<SyntaxExpression> expressionList(ASTNode node, String kind) {
        ASTNode clause = ast.child(node, kind);
        return clause == null ? Collections.<SyntaxExpression>emptyList() : expressions.expressions(clause, 0);
    }

    private GroupingSyntax grouping(ASTNode node) {
        ASTNode grouping = ast.child(node, "TOK_GROUPBY");
        GroupingKindSyntax kind = GroupingKindSyntax.ORDINARY;
        if (grouping == null) {
            grouping = ast.child(node, "TOK_ROLLUP_GROUPBY");
            kind = GroupingKindSyntax.ROLLUP;
        }
        if (grouping == null) {
            grouping = ast.child(node, "TOK_CUBE_GROUPBY");
            kind = GroupingKindSyntax.CUBE;
        }
        if (grouping == null) {
            grouping = ast.child(node, "TOK_GROUPING_SETS");
            kind = GroupingKindSyntax.GROUPING_SETS;
        }
        if (grouping == null) {
            return null;
        }
        List<List<SyntaxExpression>> groups = new ArrayList<List<SyntaxExpression>>();
        if (kind == GroupingKindSyntax.GROUPING_SETS) {
            for (ASTNode group : ast.children(grouping)) {
                if ("TOK_GROUPING_SETS_EXPRESSION".equals(ast.kind(group))) {
                    groups.add(expressions.expressions(group, 0));
                }
            }
        } else {
            groups.add(expressions.expressions(grouping, 0));
        }
        return new GroupingSyntax(kind, groups);
    }

    private LimitSyntax limit(ASTNode node) {
        if (node == null) {
            return null;
        }
        return new LimitSyntax(node.getChildCount() == 2 ? expressions.expression(ast.at(node, 0)) : null,
                expressions.expression(ast.at(node, node.getChildCount() - 1)));
    }

    FromSyntax from(ASTNode node) {
        String kind = ast.kind(node);
        if ("TOK_TABREF".equals(kind)) {
            IdentifierPart alias = null;
            for (ASTNode child : ast.children(node)) {
                if ("Identifier".equals(ast.kind(child))) {
                    alias = ast.part(child);
                }
            }
            return new NamedFromSyntax(ast.id(node), ast.ref(node), ast.name(ast.child(node, "TOK_TABNAME")), alias, sample(node));
        }
        if ("TOK_SUBQUERY".equals(kind)) {
            return new DerivedFromSyntax(ast.id(node), ast.ref(node), query(ast.at(node, 0)), ast.part(ast.at(node, 1)),
                    Collections.<IdentifierPart>emptyList(), ast.synthetic(ast.at(node, 1)));
        }
        if (kind.startsWith("TOK_LATERAL_VIEW")) {
            return lateral(node);
        }
        if (kind.contains("JOIN")) {
            return join(node);
        }
        throw new IllegalArgumentException("Unrecognized FROM structure: " + kind);
    }

    private FromSyntax join(ASTNode node) {
        FromSyntax left = from(ast.at(node, 0));
        FromSyntax right = from(ast.at(node, 1));
        String kind = ast.kind(node);
        if (node.getChildCount() == 2 && "TOK_JOIN".equals(kind)
                && ast.betweenHas(ast.at(node, 0), ast.at(node, 1), "COMMA")) {
            return new CommaFromSyntax(ast.id(node), ast.ref(node), java.util.Arrays.asList(left, right));
        }
        JoinTypeSyntax type = JoinTypeSyntax.INNER;
        if ("TOK_LEFTOUTERJOIN".equals(kind)) {
            type = JoinTypeSyntax.LEFT_OUTER;
        } else if ("TOK_RIGHTOUTERJOIN".equals(kind)) {
            type = JoinTypeSyntax.RIGHT_OUTER;
        } else if ("TOK_FULLOUTERJOIN".equals(kind)) {
            type = JoinTypeSyntax.FULL_OUTER;
        } else if ("TOK_LEFTSEMIJOIN".equals(kind)) {
            type = JoinTypeSyntax.LEFT_SEMI;
        } else if ("TOK_LEFTANTISEMIJOIN".equals(kind)) {
            type = JoinTypeSyntax.LEFT_ANTI;
        } else if ("TOK_CROSSJOIN".equals(kind)) {
            type = JoinTypeSyntax.CROSS;
        }
        SyntaxExpression on = node.getChildCount() > 2 ? expressions.expression(ast.at(node, 2)) : null;
        return new JoinFromSyntax(ast.id(node), ast.ref(node), new JoinOperandsSyntax(left, right), type,
                new JoinConditionSyntax(on == null ? JoinConditionKindSyntax.NONE : JoinConditionKindSyntax.ON,
                        on, Collections.<IdentifierPart>emptyList()));
    }

    private FromSyntax lateral(ASTNode node) {
        ASTNode select = ast.at(ast.at(node, 0), 0);
        CallSyntax function = (CallSyntax) expressions.expression(ast.at(select, 0));
        ASTNode aliasNode = ast.child(select, "TOK_TABALIAS");
        List<IdentifierPart> columns = new ArrayList<IdentifierPart>();
        for (int index = 1; index < select.getChildCount(); index++) {
            ASTNode child = ast.at(select, index);
            if (!"TOK_TABALIAS".equals(ast.kind(child))) {
                columns.add(ast.part(child));
            }
        }
        GenerateSyntax generator = new GenerateSyntax(function, ast.part(ast.at(aliasNode, 0)), columns,
                ast.kind(node).endsWith("OUTER"));
        return new LateralFromSyntax(ast.id(node), ast.ref(node), from(ast.at(node, 1)), Collections.singletonList(generator));
    }

    private TableSampleSyntax sample(ASTNode node) {
        ASTNode sample = ast.child(node, "TOK_TABLEBUCKETSAMPLE");
        if (sample != null) {
            return new TableSampleSyntax(SampleKindSyntax.BUCKET, expressions.expressions(sample, 0));
        }
        sample = ast.child(node, "TOK_TABLESPLITSAMPLE");
        return sample == null ? null : new TableSampleSyntax(SampleKindSyntax.PERCENT, expressions.expressions(sample, 0));
    }

    QueryTailSyntax emptyTail() {
        return new QueryTailSyntax(Collections.<SortItemSyntax>emptyList(), Collections.<SyntaxExpression>emptyList(),
                Collections.<SortItemSyntax>emptyList(), Collections.<SyntaxExpression>emptyList(), null);
    }

    private boolean isSet(String kind) {
        return kind.startsWith("TOK_UNION") || kind.startsWith("TOK_INTERSECT") || kind.startsWith("TOK_EXCEPT");
    }
}
