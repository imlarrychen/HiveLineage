package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class SyntaxModelContractTest {
    @Test
    public void queryCanBeConstructedWithoutParserSqlOrMetadata() {
        FromSyntax left = new NamedFromSyntax(id("left"), ref(), name("ods", "orders"), part("a"), null);
        FromSyntax right = new NamedFromSyntax(id("right"), ref(), name("ods", "orders"), part("b"), null);
        SyntaxExpression predicate = new BinarySyntax(id("eq"), ref(), BinaryOperatorSyntax.EQUAL,
                new NameRefSyntax(id("left-id"), ref(), name("a", "id")),
                new NameRefSyntax(id("right-id"), ref(), name("b", "id")));
        JoinFromSyntax join = new JoinFromSyntax(id("join"), ref(), new JoinOperandsSyntax(left, right),
                JoinTypeSyntax.LEFT_OUTER, new JoinConditionSyntax(JoinConditionKindSyntax.ON, predicate,
                        Collections.<IdentifierPart>emptyList()));
        List<SelectItemSyntax> selectItems = Arrays.asList(
                new SelectItemSyntax(new NameRefSyntax(id("project-a"), ref(), name("a", "id")), part("id")),
                new SelectItemSyntax(new NameRefSyntax(id("project-b"), ref(), name("b", "id")), part("id")));
        SelectQuerySyntax query = select("query", selectItems, join);

        Assert.assertEquals(2, query.getBody().getSelectItems().size());
        Assert.assertEquals("id", query.getBody().getSelectItems().get(0).getAlias().getValue());
        Assert.assertEquals("id", query.getBody().getSelectItems().get(1).getAlias().getValue());
        Assert.assertSame(predicate, join.getCondition().getOn());
        Assert.assertNotEquals(left.getNodeId(), right.getNodeId());
    }

    @Test
    public void starsNamesAndNestedQueriesRemainStructural() {
        StarSyntax star = new StarSyntax(id("star"), ref(), name("a"));
        SelectQuerySyntax inner = select("inner", Collections.singletonList(new SelectItemSyntax(star, null)), null);
        SubqueryExpressionSyntax exists = new SubqueryExpressionSyntax(id("exists"), ref(), SubqueryKindSyntax.EXISTS, inner, null);
        InListSyntax in = new InListSyntax(id("in"), ref(), new NameRefSyntax(id("id"), ref(), name("a", "id")),
                Collections.<SyntaxExpression>singletonList(literal("one", "1")), false);
        BetweenSyntax between = new BetweenSyntax(id("between"), ref(), in, literal("low", "1"), literal("high", "9"), true);

        Assert.assertEquals(ExpressionKind.STAR, star.getKind());
        Assert.assertEquals("a", star.getQualifier().getParts().get(0).getValue());
        Assert.assertSame(inner, exists.getQuery());
        Assert.assertEquals(ExpressionKind.IN_LIST, in.getKind());
        Assert.assertTrue(between.isNegated());
    }

    @Test
    public void positionalBranchesAndPartitionSyntaxArePreserved() {
        SelectQuerySyntax query = select("query", Collections.singletonList(new SelectItemSyntax(literal("value", "1"), null)), null);
        List<PartitionEntrySyntax> partitions = Arrays.asList(
                new PartitionEntrySyntax(part("dt"), literal("partition", "20261012")),
                new PartitionEntrySyntax(part("region"), null));
        InsertTargetSyntax target = new InsertTargetSyntax(InsertTargetKindSyntax.TABLE, name("dwd", "target"), null,
                Collections.singletonList(part("id")), partitions);
        InsertBranchSyntax queryBranch = new InsertBranchSyntax(id("query-branch"), ref(), InsertModeSyntax.OVERWRITE,
                target, new QueryInsertSourceSyntax(query));
        InsertBranchSyntax valuesBranch = new InsertBranchSyntax(id("values-branch"), ref(), InsertModeSyntax.INTO,
                target, new ValuesInsertSourceSyntax(Collections.singletonList(
                        new ValuesRowSyntax(Collections.<SyntaxExpression>singletonList(literal("two", "2"))))));
        List<InsertBranchSyntax> branches = new ArrayList<InsertBranchSyntax>(Arrays.asList(queryBranch, valuesBranch));
        InsertStatementSyntax insert = new InsertStatementSyntax(id("insert"), ref(), Collections.<CteSyntax>emptyList(), null, branches);
        branches.clear();

        Assert.assertEquals(2, insert.getBranches().size());
        Assert.assertNotNull(target.getPartitions().get(0).getStaticValue());
        Assert.assertNull(target.getPartitions().get(1).getStaticValue());
        Assert.assertTrue(insert.getBranches().get(1).getSource() instanceof ValuesInsertSourceSyntax);
    }

    @Test
    public void groupingSetsAreDeeplyImmutable() {
        List<SyntaxExpression> group = new ArrayList<SyntaxExpression>();
        group.add(literal("group", "1"));
        List<List<SyntaxExpression>> sets = new ArrayList<List<SyntaxExpression>>();
        sets.add(group);
        GroupingSyntax grouping = new GroupingSyntax(GroupingKindSyntax.GROUPING_SETS, sets);
        group.clear();
        sets.clear();

        Assert.assertEquals(1, grouping.getSets().get(0).size());
        try {
            grouping.getSets().get(0).clear();
            Assert.fail("grouping sets must be deeply immutable");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals(1, grouping.getSets().size());
        }
    }

    @Test
    public void quotedIdentifierSpellingIsNotOverwrittenByNameResolution() {
        IdentifierPart identifier = new IdentifierPart("`Order`", "Order", QuoteKind.BACKTICK, ref());
        SyntaxName syntaxName = new SyntaxName(Collections.singletonList(identifier));

        Assert.assertEquals("`Order`", syntaxName.getParts().get(0).getSpelling());
        Assert.assertEquals(QuoteKind.BACKTICK, identifier.getQuoteKind());
    }

    @Test(expected = IllegalArgumentException.class)
    public void successfulNormalizationRequiresStatement() {
        new SyntaxResult(null, Collections.<Diagnostic>emptyList(), StageOutcome.SUCCESS);
    }

    @Test
    public void syntaxAndDialectArtifactsAreSeparate() {
        SelectQuerySyntax query = select("definition", Collections.singletonList(new SelectItemSyntax(literal("one", "1"), null)), null);
        QueryStatementSyntax statement = new QueryStatementSyntax(id("statement"), ref(), query);
        SyntaxTemplate template = new SyntaxTemplate(id("template"), statement, ref());
        ParseResult<Object> artifact = new ParseResult<Object>(new Object(), Collections.<Diagnostic>emptyList(), StageOutcome.SUCCESS);

        Assert.assertSame(statement, template.getStatement());
        Assert.assertNotNull(artifact.getArtifact());
        Assert.assertEquals(StatementKind.QUERY, statement.getKind());
    }

    private SelectQuerySyntax select(String identity, List<SelectItemSyntax> items, FromSyntax from) {
        QueryTailSyntax tail = new QueryTailSyntax(Collections.<SortItemSyntax>emptyList(),
                Collections.<SyntaxExpression>emptyList(), Collections.<SortItemSyntax>emptyList(),
                Collections.<SyntaxExpression>emptyList(), null);
        QueryClausesSyntax clauses = new QueryClausesSyntax(null, null, null, Collections.<NamedWindowSyntax>emptyList(), tail);
        return new SelectQuerySyntax(id(identity), ref(), new QueryBlockId(identity), Collections.<CteSyntax>emptyList(),
                new SelectBodySyntax(QuantifierSyntax.DEFAULT, items, from, clauses));
    }

    private LiteralSyntax literal(String identity, String value) {
        return new LiteralSyntax(id(identity), ref(), LiteralKind.NUMBER, value, value);
    }

    private SyntaxName name(String... parts) {
        List<IdentifierPart> identifiers = new ArrayList<IdentifierPart>();
        for (String value : parts) {
            identifiers.add(part(value));
        }
        return new SyntaxName(identifiers);
    }

    private IdentifierPart part(String value) {
        return new IdentifierPart(value, value, QuoteKind.NONE, ref());
    }

    private NodeId id(String value) {
        return new NodeId(value);
    }

    private SourceRef ref() {
        return SourceRef.synthetic("fixture");
    }
}
