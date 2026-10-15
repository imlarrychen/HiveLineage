package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.api.DialectSyntaxFrontend;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnitKind;
import com.panfan.hivelineage.v2.frontend.SourceMap;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.LexicalOptions;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.NameRefSyntax;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.ParseResult;
import com.panfan.hivelineage.v2.syntax.QueryInsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax;
import java.util.Arrays;
import java.util.Collections;
import org.antlr.runtime.CommonToken;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.junit.Assert;
import org.junit.Test;

public class HiveFrontendHardeningTest {
    private final HiveDialectFactory factory = new HiveDialectFactory();

    @Test
    public void useAcceptsOnlyIdentifiersAndNeverChangesDatabaseAfterAnInvalidCommand() {
        for (String sql : Arrays.asList("use +", "use 1", "use 'db'", "use a.b", "use select", "use true")) {
            SyntaxResult parsed = frontend(sql);
            Assert.assertTrue(sql, parsed.getStatement() instanceof OtherStatementSyntax);
            Assert.assertEquals(sql, "INVALID_USE_DATABASE", parsed.getDiagnostics().get(0).getCode());
            Assert.assertEquals(sql, 1, parsed.getDiagnostics().size());
        }
        SyntaxResult empty = frontend("use ``");
        Assert.assertTrue(empty.getStatement() instanceof OtherStatementSyntax);
        Assert.assertEquals(1, empty.getDiagnostics().stream()
                .filter(diagnostic -> "INVALID_USE_DATABASE".equals(diagnostic.getCode())).count());
        for (String sql : Arrays.asList("use ods", "use default", "use `select`", "use `a.b`")) {
            Assert.assertTrue(sql, frontend(sql).getStatement() instanceof CommandStatementSyntax);
        }
        LineageAnalyzeResult result = new HiveScriptLineageAdapter().analyzeScript(LineageAnalyzeRequest.builder()
                .workflowId("wf").taskId("task").taskVersion("v1").defaultDatabase("original")
                .rawScript("use +; insert into target(id) select id from source")
                .metadataProvider(table -> HiveTableMetadata.table(table, Collections.singletonList("id")))
                .options(LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build()).build());
        Assert.assertEquals("original.target", result.getStatements().get(1).getOutputObject().getQualifiedName());
        Assert.assertEquals("original.source", result.getStatements().get(1).getInputObjects().get(0).getQualifiedName());
    }

    @Test
    public void normalizationReportsOnlyNewDiagnosticsAndFrontendAggregatesEachOnce() {
        String sql = "select ??? from ods.t";
        ParseResult<HiveParseArtifact> parsed = factory.createParser().parse(lex(sql), options());
        Assert.assertEquals(1, parsed.getDiagnostics().size());
        SyntaxResult normalized = factory.createNormalizer().normalize(parsed.getArtifact(), options());
        Assert.assertEquals(StageOutcome.PARTIAL, normalized.getOutcome());
        Assert.assertTrue(normalized.getDiagnostics().isEmpty());
        SyntaxResult aggregate = frontend(sql);
        Assert.assertEquals(1, aggregate.getDiagnostics().stream()
                .filter(diagnostic -> "RECOVERED_PROJECTION".equals(diagnostic.getCode())).count());
    }

    @Test
    public void unknownNativeLeavesAndFunctionHeadsRemainUnknownWithKnownChildren() {
        ASTNode leaf = node(Integer.MAX_VALUE, "TOK_FUTURE_LITERAL");
        Assert.assertTrue(normalizeExpression(leaf) instanceof UnknownExpressionSyntax);
        ASTNode function = node(HiveParser.TOK_FUNCTION, "TOK_FUNCTION");
        function.addChild(node(Integer.MAX_VALUE, "TOK_FUTURE_OPERATOR"));
        ASTNode field = node(HiveParser.TOK_TABLE_OR_COL, "TOK_TABLE_OR_COL");
        field.addChild(node(HiveParser.Identifier, "id"));
        function.addChild(field);
        SyntaxExpression expression = normalizeExpression(function);
        Assert.assertTrue(expression instanceof UnknownExpressionSyntax);
        UnknownExpressionSyntax unknown = (UnknownExpressionSyntax) expression;
        Assert.assertEquals(1, unknown.getKnownChildren().size());
        Assert.assertTrue(unknown.getKnownChildren().get(0) instanceof NameRefSyntax);
    }

    @Test
    public void tokenLikeUserIdentifiersRetainTheirDeclaredNativeType() {
        SyntaxResult result = frontend("select tok_id, tok_udf(tok_id) from ods.t");
        Assert.assertEquals(StageOutcome.SUCCESS, result.getOutcome());
        SelectQuerySyntax query = (SelectQuerySyntax) ((QueryStatementSyntax) result.getStatement()).getQuery();
        Assert.assertTrue(query.getBody().getSelectItems().get(0).getExpression() instanceof NameRefSyntax);
        Assert.assertTrue(query.getBody().getSelectItems().get(1).getExpression() instanceof CallSyntax);
    }

    @Test
    public void unknownFunctionPreservesWindowPartitionOrderAndFrameExpressions() {
        ASTNode function = node(HiveParser.TOK_FUNCTION, "TOK_FUNCTION");
        function.addChild(node(Integer.MAX_VALUE, "TOK_FUTURE_OPERATOR"));
        function.addChild(node(HiveParser.Number, "1"));
        ASTNode window = node(HiveParser.TOK_WINDOWSPEC, "TOK_WINDOWSPEC");
        ASTNode partitioning = node(HiveParser.TOK_PARTITIONINGSPEC, "TOK_PARTITIONINGSPEC");
        ASTNode partition = node(HiveParser.TOK_DISTRIBUTEBY, "TOK_DISTRIBUTEBY");
        partition.addChild(field("partition_id"));
        partitioning.addChild(partition);
        ASTNode order = node(HiveParser.TOK_ORDERBY, "TOK_ORDERBY");
        ASTNode sort = node(HiveParser.TOK_TABSORTCOLNAMEASC, "TOK_TABSORTCOLNAMEASC");
        sort.addChild(field("order_id"));
        order.addChild(sort);
        partitioning.addChild(order);
        window.addChild(partitioning);
        ASTNode frame = node(HiveParser.TOK_WINDOWRANGE, "TOK_WINDOWRANGE");
        ASTNode preceding = node(HiveParser.KW_PRECEDING, "preceding");
        preceding.addChild(field("offset_id"));
        frame.addChild(preceding);
        window.addChild(frame);
        function.addChild(window);
        UnknownExpressionSyntax unknown = (UnknownExpressionSyntax) normalizeExpression(function);
        Assert.assertEquals(4, unknown.getKnownChildren().size());
        for (int index = 1; index < unknown.getKnownChildren().size(); index++) {
            Assert.assertTrue(unknown.getKnownChildren().get(index) instanceof NameRefSyntax);
        }
    }

    @Test
    public void deepAndWideAstBudgetsFailBeforeRecursiveConversionAndLeaveFrontendReusable() {
        ASTNode deep = node(HiveParser.Number, "1");
        for (int index = 0; index < 10000; index++) {
            ASTNode parent = node(HiveParser.PLUS, "+");
            parent.addChild(deep);
            parent.addChild(node(HiveParser.Number, "1"));
            deep = parent;
        }
        SyntaxResult depth = factory.createNormalizer().normalize(new HiveParseArtifact(lex("select 1"), deep, null),
                new SyntaxOptions(factory.getProfile(), false, 512, 1000, 100000, 32));
        Assert.assertEquals(StageOutcome.FAILED, depth.getOutcome());
        Assert.assertEquals("NORMALIZATION_DEPTH_LIMIT", depth.getDiagnostics().get(0).getCode());
        ASTNode wide = node(HiveParser.TOK_QUERY, "TOK_QUERY");
        for (int index = 0; index < 10000; index++) {
            wide.addChild(node(HiveParser.Number, "1"));
        }
        SyntaxResult count = factory.createNormalizer().normalize(new HiveParseArtifact(lex("select 1"), wide, null),
                new SyntaxOptions(factory.getProfile(), false, 512, 1000, 2, 32));
        Assert.assertEquals("NORMALIZATION_NODE_LIMIT", count.getDiagnostics().get(0).getCode());
        Assert.assertEquals(StageOutcome.SUCCESS, frontend("select 1").getOutcome());
    }

    @Test
    public void multiInsertSharesTheNormalizedSourceAndCteDefinitions() {
        InsertStatementSyntax statement = (InsertStatementSyntax) frontend("with c as (select id from ods.t) "
                + "from c a insert into dwd.x select a.id insert overwrite table dwd.y select a.id").getStatement();
        SelectQuerySyntax first = (SelectQuerySyntax) ((QueryInsertSourceSyntax) statement.getBranches().get(0).getSource()).getQuery();
        SelectQuerySyntax second = (SelectQuerySyntax) ((QueryInsertSourceSyntax) statement.getBranches().get(1).getSource()).getQuery();
        Assert.assertSame(first.getBody().getFrom(), second.getBody().getFrom());
        Assert.assertSame(statement.getSharedFrom(), first.getBody().getFrom());
        Assert.assertSame(first.getCtes().get(0), second.getCtes().get(0));
        Assert.assertSame(statement.getCtes().get(0), first.getCtes().get(0));
    }

    private SyntaxExpression normalizeExpression(ASTNode tree) {
        HiveAstSupport ast = new HiveAstSupport(new HiveParseArtifact(lex("select 1"), tree, null), options());
        SyntaxExpression expression = new HiveQueryNormalizer(ast).expressions().expression(tree);
        Assert.assertEquals(1, ast.diagnostics().size());
        return expression;
    }

    private ASTNode node(int type, String text) {
        return new ASTNode(new CommonToken(type, text));
    }

    private ASTNode field(String name) {
        ASTNode field = node(HiveParser.TOK_TABLE_OR_COL, "TOK_TABLE_OR_COL");
        field.addChild(node(HiveParser.Identifier, name));
        return field;
    }

    private SyntaxResult frontend(String sql) {
        return new DialectSyntaxFrontend<HiveParseArtifact>(factory).parse(expanded(sql), factory.getProfile());
    }

    private LexedUnit lex(String sql) {
        return factory.createLexer().tokenize(expanded(sql), new LexicalOptions(factory.getProfile(), true)).getUnit();
    }

    private ExpandedUnit expanded(String sql) {
        ScriptUnit unit = new ScriptUnit("test", 0, sql, ScriptUnitKind.SQL, SourceRef.synthetic("test"));
        return new ExpandedUnit(unit, "test:expanded", sql, new SourceMap("test", "test:expanded", Collections.emptyList()),
                Collections.emptyList());
    }

    private SyntaxOptions options() {
        return new SyntaxOptions(factory.getProfile(), true);
    }
}
