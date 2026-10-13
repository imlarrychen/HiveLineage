package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CommaFromSyntax;
import com.panfan.hivelineage.v2.syntax.CommandStatementSyntax;
import com.panfan.hivelineage.v2.syntax.CommandSyntax;
import com.panfan.hivelineage.v2.syntax.DefinitionStatementSyntax;
import com.panfan.hivelineage.v2.syntax.DeleteStatementSyntax;
import com.panfan.hivelineage.v2.syntax.InsertBranchSyntax;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.InsertStatementSyntax;
import com.panfan.hivelineage.v2.syntax.JoinFromSyntax;
import com.panfan.hivelineage.v2.syntax.JoinTypeSyntax;
import com.panfan.hivelineage.v2.syntax.LateralFromSyntax;
import com.panfan.hivelineage.v2.syntax.MergeStatementSyntax;
import com.panfan.hivelineage.v2.syntax.ParseResult;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SelectQuerySyntax;
import com.panfan.hivelineage.v2.syntax.SetCommandSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import com.panfan.hivelineage.v2.syntax.UpdateStatementSyntax;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;
import com.panfan.hivelineage.v2.syntax.UnaryOperatorSyntax;
import com.panfan.hivelineage.v2.syntax.LiteralSyntax;
import com.panfan.hivelineage.v2.syntax.FunctionCommandSyntax;
import com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.ValuesInsertSourceSyntax;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.frontend.DefaultVariableResolver;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.ScriptOptions;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnitKind;
import com.panfan.hivelineage.v2.frontend.SourceMap;
import com.panfan.hivelineage.v2.frontend.SourceMapEntry;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.frontend.VariableContext;
import com.panfan.hivelineage.v2.frontend.VariableEvidence;
import com.panfan.hivelineage.v2.frontend.VariableOptions;
import com.panfan.hivelineage.v2.frontend.VariableResolutionPolicy;
import com.panfan.hivelineage.v2.lexical.LexResult;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.LexicalOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

public class HiveFrontendTest {
    private final HiveDialectFactory factory = new HiveDialectFactory();

    @Test
    public void parserConsumesReusableTokensRatherThanRetokenizingText() {
        LexedUnit original = lex("select a.id from ods.t a");
        ExpandedUnit unrelatedText = expanded("not SQL and never tokenized again");
        LexedUnit explicitInput = new LexedUnit(unrelatedText, original.getTokens(), original.getTrivia());
        ParseResult<HiveParseArtifact> first = factory.createParser().parse(explicitInput, syntaxOptions());
        ParseResult<HiveParseArtifact> second = factory.createParser().parse(explicitInput, syntaxOptions());
        Assert.assertEquals(StageOutcome.SUCCESS, first.getOutcome());
        Assert.assertEquals(StageOutcome.SUCCESS, second.getOutcome());
        Assert.assertEquals(first.getArtifact().tree().toStringTree(), second.getArtifact().tree().toStringTree());
    }

    @Test
    public void parserRejectsDeepTokenStructureBeforeNativeRecursion() {
        StringBuilder sql = new StringBuilder("select ");
        for (int index = 0; index < 5000; index++) {
            sql.append('(');
        }
        sql.append('1');
        for (int index = 0; index < 5000; index++) {
            sql.append(')');
        }
        ParseResult<HiveParseArtifact> parsed = factory.createParser().parse(lex(sql.toString()), syntaxOptions());
        Assert.assertEquals(StageOutcome.FAILED, parsed.getOutcome());
        Assert.assertEquals("PARSER_NESTING_LIMIT", parsed.getDiagnostics().get(0).getCode());
        Assert.assertEquals(StageOutcome.SUCCESS, factory.createParser().parse(
                lex("select '(((([[[[' /* ((([[[ */"), new SyntaxOptions(factory.getProfile(), false, 1, 100)).getOutcome());
        Assert.assertEquals("PARSER_TOKEN_LIMIT", factory.createParser().parse(lex("select 1"),
                new SyntaxOptions(factory.getProfile(), false, 512, 1)).getDiagnostics().get(0).getCode());
    }

    @Test
    public void normalizerPreservesTargetColumnsOverwriteAndPartitions() {
        InsertStatementSyntax statement = (InsertStatementSyntax) normalize(
                "insert overwrite table dwd.t partition(ds='2026',dt) (id) select a.id, a.dt from ods.t a");
        InsertBranchSyntax branch = statement.getBranches().get(0);
        Assert.assertEquals(InsertModeSyntax.OVERWRITE, branch.getMode());
        Assert.assertEquals("id", branch.getTarget().getTargetColumns().get(0).getValue());
        Assert.assertNotNull(branch.getTarget().getPartitions().get(0).getStaticValue());
        Assert.assertNull(branch.getTarget().getPartitions().get(1).getStaticValue());
    }

    @Test
    public void caseWindowSubqueryAndGenerateAreTypedSyntax() {
        QueryStatementSyntax statement = (QueryStatementSyntax) normalize("select case when a.id>0 then a.id else 0 end x, "
                + "row_number() over(partition by a.id order by a.id desc) r, "
                + "(select count(*) from ods.u u where u.id=a.id) c "
                + "from ods.t a lateral view explode(a.tags) e as tag");
        SelectQuerySyntax query = (SelectQuerySyntax) statement.getQuery();
        Assert.assertTrue(query.getBody().getSelectItems().get(0).getExpression() instanceof CaseSyntax);
        Assert.assertTrue(query.getBody().getSelectItems().get(1).getExpression() instanceof WindowCallSyntax);
        Assert.assertTrue(query.getBody().getSelectItems().get(2).getExpression() instanceof SubqueryExpressionSyntax);
        Assert.assertTrue(query.getBody().getFrom() instanceof LateralFromSyntax);
        WindowCallSyntax window = (WindowCallSyntax) query.getBody().getSelectItems().get(1).getExpression();
        Assert.assertEquals(1, window.getWindow().getPartitionBy().size());
        Assert.assertEquals(1, window.getWindow().getOrderBy().size());
    }

    @Test
    public void bitwiseNotAndConcatenatedStringLiteralsPreserveNativeSyntax() {
        SelectQuerySyntax query = (SelectQuerySyntax) ((QueryStatementSyntax) normalize(
                "select ~id, 'ab' 'CD' from ods.t")).getQuery();
        Assert.assertEquals(UnaryOperatorSyntax.BITWISE_NOT,
                ((UnarySyntax) query.getBody().getSelectItems().get(0).getExpression()).getOperator());
        Assert.assertEquals("abCD", ((LiteralSyntax) query.getBody().getSelectItems().get(1).getExpression()).getDecodedValue());
    }

    @Test
    public void ctasAndViewDefinitionsKeepCtesAndDeclaredColumnOrder() {
        DefinitionStatementSyntax table = (DefinitionStatementSyntax) normalize(
                "create temporary table tmp as with x as(select id from ods.t) select * from x");
        Assert.assertTrue(table.getDefinition().getOptions().isTemporary());
        Assert.assertEquals(1, table.getDefinition().getQuery().getCtes().size());
        DefinitionStatementSyntax view = (DefinitionStatementSyntax) normalize(
                "create view dim.v (key comment 'key', val comment 'value') as select id, name from ods.t");
        Assert.assertEquals("key", view.getDefinition().getSchema().getColumns().get(0).getName().getValue());
        Assert.assertEquals("val", view.getDefinition().getSchema().getColumns().get(1).getName().getValue());
        DefinitionStatementSyntax parenthesized = (DefinitionStatementSyntax) normalize(
                "create view dim.v(key) as (select id from ods.t)");
        Assert.assertEquals("key", parenthesized.getDefinition().getSchema().getColumns().get(0).getName().getValue());
    }

    @Test
    public void commaJoinAndExplicitJoinRemainDistinct() {
        SelectQuerySyntax comma = (SelectQuerySyntax) ((QueryStatementSyntax) normalize(
                "select a.id from ods.joined a, ods.u b where a.id=b.id")).getQuery();
        SelectQuerySyntax cross = (SelectQuerySyntax) ((QueryStatementSyntax) normalize(
                "select a.id from ods.t a cross join ods.u b")).getQuery();
        Assert.assertTrue(comma.getBody().getFrom() instanceof CommaFromSyntax);
        Assert.assertEquals(JoinTypeSyntax.CROSS, ((JoinFromSyntax) cross.getBody().getFrom()).getJoinType());
    }

    @Test
    public void commandsAndMutationsHaveIndependentSyntaxShapes() {
        CommandStatementSyntax command = (CommandStatementSyntax) normalize("set hivevar:day = '20261012'");
        SetCommandSyntax set = (SetCommandSyntax) command.getCommand();
        Assert.assertEquals("hivevar", set.getNamespace());
        Assert.assertEquals("20261012", set.getValue());
        Assert.assertTrue(normalize("delete from dwd.t where id=1") instanceof DeleteStatementSyntax);
        Assert.assertTrue(normalize("update dwd.t set amount=amount+1 where id=1") instanceof UpdateStatementSyntax);
        MergeStatementSyntax merge = (MergeStatementSyntax) normalize("merge into dwd.t t using ods.s s on t.id=s.id "
                + "when matched then update set amount=s.amount when not matched then insert values(s.id,s.amount)");
        Assert.assertEquals(2, merge.getBody().getClauses().size());
    }

    @Test
    public void functionClassLiteralsAreNotConfusedWithQuotedSelectAliases() {
        FunctionCommandSyntax create = (FunctionCommandSyntax) ((CommandStatementSyntax) normalize(
                "create temporary function my_fn as 'pkg.F' using jar 'hdfs:///f.jar'")).getCommand();
        Assert.assertTrue(create.isTemporary());
        Assert.assertFalse(create.isDrop());
        Assert.assertEquals("pkg.F", create.getClassName());
        Assert.assertEquals("jar", create.getResources().get(0).getResourceType());
        Assert.assertEquals("hdfs:///f.jar", create.getResources().get(0).getUri());
        FunctionCommandSyntax drop = (FunctionCommandSyntax) ((CommandStatementSyntax) normalize(
                "drop temporary function if exists my_fn")).getCommand();
        Assert.assertTrue(drop.isDrop());
        Assert.assertTrue(drop.isTemporary());
    }

    @Test
    public void valuesAndMultipleInsertBranchesPreserveTheirSyntax() {
        InsertStatementSyntax values = (InsertStatementSyntax) normalize("insert into dwd.t(id,name) values(1,'a'),(2,'b')");
        Assert.assertEquals(2, ((ValuesInsertSourceSyntax) values.getBranches().get(0).getSource()).getRows().size());
        InsertStatementSyntax multiple = (InsertStatementSyntax) normalize("from ods.t a insert into dwd.x select a.id "
                + "insert overwrite table dwd.y select a.name");
        Assert.assertEquals(2, multiple.getBranches().size());
        Assert.assertEquals(InsertModeSyntax.INTO, multiple.getBranches().get(0).getMode());
        Assert.assertEquals(InsertModeSyntax.OVERWRITE, multiple.getBranches().get(1).getMode());
    }

    @Test
    public void recoveryRetainsOpaqueProjectionAndCteColumnAliases() {
        LexResult lexed = factory.createLexer().tokenize(expanded(
                "with c(`ID`, name) as(select id,name from db1.a) select ??? from c join db2.b b on c.id=b.id"),
                new LexicalOptions(factory.getProfile(), true));
        Assert.assertNotEquals(StageOutcome.FAILED, lexed.getOutcome());
        LexedUnit unit = lexed.getUnit();
        ParseResult<HiveParseArtifact> parsed = factory.createParser().parse(unit, new SyntaxOptions(factory.getProfile(), true));
        Assert.assertEquals(StageOutcome.PARTIAL, parsed.getOutcome());
        SyntaxResult normalized = factory.createNormalizer().normalize(parsed.getArtifact(), syntaxOptions());
        SelectQuerySyntax query = (SelectQuerySyntax) ((QueryStatementSyntax) normalized.getStatement()).getQuery();
        Assert.assertTrue(query.getBody().getSelectItems().get(0).getExpression() instanceof UnknownExpressionSyntax);
        Assert.assertEquals("ID", query.getCtes().get(0).getColumnAliases().get(0).getValue());
        Assert.assertEquals("name", query.getCtes().get(0).getColumnAliases().get(1).getValue());
    }

    @Test
    public void quotedSelectAliasesAreGrammarExtensionsWithoutChangingTheirSpelling() {
        SelectQuerySyntax query = (SelectQuerySyntax) ((QueryStatementSyntax) normalize("select `select` as 'select' from t")).getQuery();
        Assert.assertEquals("select", query.getBody().getSelectItems().get(0).getAlias().getValue());
        Assert.assertEquals("'select'", query.getBody().getSelectItems().get(0).getAlias().getSpelling());
    }

    @Test
    public void malformedDdlIsAuditedButMalformedDataQueryStillFails() {
        ParseResult<HiveParseArtifact> ddl = factory.createParser().parse(lex("create table bad (id)"), syntaxOptions());
        Assert.assertEquals(StageOutcome.PARTIAL, ddl.getOutcome());
        SyntaxResult syntax = factory.createNormalizer().normalize(ddl.getArtifact(), syntaxOptions());
        Assert.assertEquals("INVALID_DDL", ((com.panfan.hivelineage.v2.syntax.OtherStatementSyntax) syntax.getStatement()).getUnsupportedReason());
        Assert.assertEquals(StageOutcome.FAILED, factory.createParser().parse(lex("create table bad as select from"), syntaxOptions()).getOutcome());
    }

    @Test
    public void generatedCorpusHasOnlyItsDeclaredSyntaxFailureAndRecovers() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(Paths.get("src/test/resources/hql/generated"))) {
            files = paths.filter(path -> path.toString().endsWith(".sql")).sorted().collect(Collectors.toList());
        }
        Assert.assertTrue("baseline SQL corpus must remain present", files.size() >= 18);
        int failures = 0;
        int statements = 0;
        for (Path path : files) {
            String script = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            Map<String, Map<String, String>> variables = new LinkedHashMap<String, Map<String, String>>();
            for (ScriptUnit unit : new DefaultScriptPreprocessor().preprocess(path.toString(), script,
                    new ScriptOptions(";", true, 10000)).getUnits()) {
                statements++;
                ExpandedUnit expanded = new DefaultVariableResolver().expand(unit, new VariableContext(variables),
                        new VariableOptions(VariableResolutionPolicy.LEGACY_FALLBACK, "20260701", 1000000)).getUnit();
                LexResult lexed = factory.createLexer().tokenize(expanded, new LexicalOptions(factory.getProfile(), true));
                Assert.assertEquals(path.toString(), StageOutcome.SUCCESS, lexed.getOutcome());
                ParseResult<HiveParseArtifact> parsed = factory.createParser().parse(lexed.getUnit(), syntaxOptions());
                if (parsed.getOutcome() == StageOutcome.FAILED) {
                    failures++;
                    Assert.assertEquals("lineage_case_11.sql", path.getFileName().toString());
                    Assert.assertEquals(0, unit.getStatementIndex());
                    Assert.assertEquals(AnalysisStage.SYNTAX, parsed.getDiagnostics().get(0).getStage());
                    continue;
                }
                SyntaxResult normalized = factory.createNormalizer().normalize(parsed.getArtifact(), syntaxOptions());
                Assert.assertEquals(path + ":" + unit.getStatementIndex(), StageOutcome.SUCCESS, normalized.getOutcome());
                rememberSet(normalized.getStatement(), variables);
            }
        }
        Assert.assertEquals(1, failures);
        Assert.assertTrue(statements >= 48);
    }

    private void rememberSet(SyntaxStatement statement, Map<String, Map<String, String>> variables) {
        if (!(statement instanceof CommandStatementSyntax)) {
            return;
        }
        CommandSyntax command = ((CommandStatementSyntax) statement).getCommand();
        if (!(command instanceof SetCommandSyntax)) {
            return;
        }
        SetCommandSyntax set = (SetCommandSyntax) command;
        Map<String, String> namespace = variables.get(set.getNamespace());
        if (namespace == null) {
            namespace = new LinkedHashMap<String, String>();
            variables.put(set.getNamespace(), namespace);
        }
        namespace.put(set.getKey(), set.getValue());
    }

    private SyntaxStatement normalize(String sql) {
        ParseResult<HiveParseArtifact> parsed = factory.createParser().parse(lex(sql), syntaxOptions());
        Assert.assertEquals(parsed.getDiagnostics().toString(), StageOutcome.SUCCESS, parsed.getOutcome());
        SyntaxResult normalized = factory.createNormalizer().normalize(parsed.getArtifact(), syntaxOptions());
        Assert.assertEquals(normalized.getDiagnostics().toString(), StageOutcome.SUCCESS, normalized.getOutcome());
        return normalized.getStatement();
    }

    private LexedUnit lex(String sql) {
        LexResult result = factory.createLexer().tokenize(expanded(sql), new LexicalOptions(factory.getProfile(), true));
        Assert.assertEquals(StageOutcome.SUCCESS, result.getOutcome());
        return result.getUnit();
    }

    private ExpandedUnit expanded(String sql) {
        ScriptUnit script = new ScriptUnit("test", 0, sql, ScriptUnitKind.SQL, SourceRef.synthetic("test"));
        return new ExpandedUnit(script, "test:expanded", sql,
                new SourceMap("test", "test:expanded", Collections.<SourceMapEntry>emptyList()),
                Collections.<VariableEvidence>emptyList());
    }

    private SyntaxOptions syntaxOptions() {
        return new SyntaxOptions(factory.getProfile(), false);
    }
}
