package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnitKind;
import com.panfan.hivelineage.v2.frontend.SourceMap;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.LexicalOptions;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.SyntaxQuery;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.antlr.runtime.CommonToken;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.junit.Assert;
import org.junit.Test;

/** Independent service contracts preserve AST identity, source and cached recursive conversion. */
public class HiveNormalizationServiceContractTest {
    private final HiveDialectFactory factory = new HiveDialectFactory();

    @Test
    public void astIndexRetainsPreorderIdentitiesAndNativeIdentifierVocabulary() {
        ASTNode root = node(HiveParser.PLUS, "+");
        ASTNode first = node(HiveParser.Identifier, "TOK_QUERY");
        ASTNode second = node(HiveParser.Number, "2");
        root.addChild(first);
        root.addChild(second);
        HiveAstSupport ast = support(root);
        Assert.assertEquals("test:expanded:node:0", ast.id(root).getValue());
        Assert.assertEquals("test:expanded:node:1", ast.id(first).getValue());
        Assert.assertEquals("test:expanded:node:2", ast.id(second).getValue());
        Assert.assertEquals("test:expanded:node:0:query", ast.block(root).getValue());
        Assert.assertEquals("Identifier", ast.kind(first));
    }

    @Test
    public void diagnosticsAreOwnedSnapshotsRatherThanAnExposedMutableAccumulator() {
        ASTNode node = node(Integer.MAX_VALUE, "TOK_FUTURE");
        HiveAstSupport ast = support(node);
        List<Diagnostic> before = ast.diagnostics();
        ast.unsupported(node, "first");
        List<Diagnostic> after = ast.diagnostics();
        ast.unsupported(node, "second");
        Assert.assertTrue(before.isEmpty());
        Assert.assertEquals(1, after.size());
        Assert.assertEquals(2, ast.diagnostics().size());
        try {
            after.clear();
            Assert.fail("Diagnostic snapshots must be immutable");
        } catch (UnsupportedOperationException expected) {
            Assert.assertEquals("first", after.get(0).getMessage());
        }
    }

    @Test
    public void subqueriesUseOnlyTheirNarrowCallbackAndReuseTheExpressionCache() {
        ASTNode queryNode = node(HiveParser.TOK_QUERY, "TOK_QUERY");
        ASTNode subquery = node(HiveParser.TOK_SUBQUERY_EXPR, "TOK_SUBQUERY_EXPR");
        subquery.addChild(node(HiveParser.TOK_SUBQUERY_OP, "TOK_SUBQUERY_OP"));
        subquery.addChild(queryNode);
        HiveParseArtifact parsed = factory.createParser().parse(lex("select 1"), options()).getArtifact();
        SyntaxQuery expected = ((QueryStatementSyntax) factory.createNormalizer().normalize(parsed, options()).getStatement()).getQuery();
        AtomicInteger calls = new AtomicInteger();
        HiveExpressionNormalizer expressions = new HiveExpressionNormalizer(support(subquery), nativeQuery -> {
            Assert.assertSame(queryNode, nativeQuery);
            calls.incrementAndGet();
            return expected;
        });
        SubqueryExpressionSyntax normalized = (SubqueryExpressionSyntax) expressions.expression(subquery);
        Assert.assertSame(expected, normalized.getQuery());
        Assert.assertSame(normalized, expressions.expression(subquery));
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void castAndWindowHandlersReenterTheSameCachedExpressionConversion() {
        ASTNode argument = node(HiveParser.Number, "1");
        ASTNode cast = node(HiveParser.TOK_FUNCTION, "TOK_FUNCTION");
        cast.addChild(node(HiveParser.TOK_INT, "TOK_INT"));
        cast.addChild(argument);
        ASTNode call = node(HiveParser.TOK_FUNCTION, "TOK_FUNCTION");
        call.addChild(node(HiveParser.Identifier, "sum"));
        call.addChild(cast);
        ASTNode window = node(HiveParser.TOK_WINDOWSPEC, "TOK_WINDOWSPEC");
        ASTNode partitioning = node(HiveParser.TOK_PARTITIONINGSPEC, "TOK_PARTITIONINGSPEC");
        ASTNode partition = node(HiveParser.TOK_DISTRIBUTEBY, "TOK_DISTRIBUTEBY");
        partition.addChild(argument);
        partitioning.addChild(partition);
        window.addChild(partitioning);
        call.addChild(window);
        HiveExpressionNormalizer expressions = new HiveExpressionNormalizer(support(call), query -> {
            throw new AssertionError("A scalar call does not request a query");
        });
        WindowCallSyntax normalizedWindow = (WindowCallSyntax) expressions.expression(call);
        CallSyntax normalized = normalizedWindow.getCall();
        CastSyntax normalizedCast = (CastSyntax) expressions.expression(cast);
        Assert.assertSame(normalizedCast, normalized.getArguments().getArguments().get(0));
        Assert.assertSame(expressions.expression(argument), normalizedCast.getExpression());
        Assert.assertSame(normalizedCast.getExpression(), normalizedWindow.getWindow().getPartitionBy().get(0));
    }

    private HiveAstSupport support(ASTNode root) {
        return new HiveAstSupport(new HiveParseArtifact(lex("select 1"), root, null), options());
    }

    private ASTNode node(int type, String text) {
        return new ASTNode(new CommonToken(type, text));
    }

    private LexedUnit lex(String sql) {
        ScriptUnit unit = new ScriptUnit("test", 0, sql, ScriptUnitKind.SQL, SourceRef.synthetic("test"));
        ExpandedUnit expanded = new ExpandedUnit(unit, "test:expanded", sql, new SourceMap("test", "test:expanded", Collections.emptyList()),
                Collections.emptyList());
        return factory.createLexer().tokenize(expanded, new LexicalOptions(factory.getProfile(), true)).getUnit();
    }

    private SyntaxOptions options() {
        return new SyntaxOptions(factory.getProfile(), true);
    }
}
