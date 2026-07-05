package com.panfan.hivelineage;

import com.panfan.hivelineage.core.*;
import com.panfan.hivelineage.metadata.*;
import com.panfan.hivelineage.request.*;
import com.panfan.hivelineage.result.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class HiveLineageAnalyzerTest {
    public static void main(String[] args) throws Exception {
        HiveLineageAnalyzerTest test = new HiveLineageAnalyzerTest();
        test.expressionColumnLineageRecordsSourceColumnsAndExpression();
        test.sessionSetVariableIsAppliedAndIgnoredStatementsAreNotReturned();
        test.includeIgnoredStatementsReturnsIgnoredResults();
        test.parseModeCanReturnOnlyTableOrOnlyColumnLineage();
        test.failedStatementIsReturnedWithNumericStatusAndRawSql();
        test.columnLineageUsesMetadataCacheToInferTargetColumns();
        test.tableOnlyModeDoesNotLoadMetadata();
        test.unresolvedVariablesAreReplacedByFallbackConstant();
        test.hivevarAndEnvironmentVariablesAreNormalized();
        test.productionSample01ExtractsJoinedSourceTables();
        test.productionSample02ExtractsUnionAndJoinSourceTables();
        test.generatedCoreLineageCasesParseWithoutFailure();
        test.unionAllColumnLineageUsesBranchSelectColumns();
        test.constantSelectItemProducesConstantLineage();
        test.unionAllConstantExpressionsAreMerged();
        test.columnLineageResolvesJoinAliasToRealSourceTables();
        test.columnLineageUsesSourceMetadataForUnqualifiedJoinColumns();
        test.createTableAsSelectProducesFormalTableLineage();
        test.temporaryTableLineageIsExpandedToFinalTargetByDefault();
        test.includeTemporaryLineageReturnsTemporaryCreateStatement();
        test.hiveParserAstAdapterParsesInsertJoinFromAst();
        test.hiveParserAstAdapterParsesCreateTableAsSelectFromAst();
        test.hiveParserAstAdapterParsesAlterViewAsSelectFromAst();
        test.fallbackParserAdapterUsesHeuristicWhenPrimaryFails();
        test.viewInputIsExpandedAndViewMetadataIsCached();
        test.commonTableExpressionUsesRealSourcesAndColumnLineage();
        test.createViewAsSelectProducesFormalLineage();
        test.viewColumnLineageExpandsToBaseColumns();
        test.dynamicPartitionColumnLineageUsesSelectField();
        test.staticPartitionColumnLineageRecordsConstantExpression();
        test.mixedStaticAndDynamicPartitionColumnsAreAligned();
        test.statementSplitterIgnoresSemicolonInsideStringAndComments();
        test.insertOverwriteDirectoryIsIgnoredBecauseThereIsNoOutputTable();
        test.selectStarExpandsColumnsFromMetadata();
        test.selectStarAlignsToTargetMetadataWhenSourceHasExtraColumns();
        test.subqueryAliasColumnLineageResolvesToBaseTable();
        test.metadataCacheExpiresPositiveAndMissingEntries();
        test.missingTargetMetadataEmitsWarningsOnStatementAndAnalyzeResult();
        test.missingViewDefinitionEmitsWarningAndKeepsViewSource();
        test.viewExpansionDepthExceededEmitsWarningAndKeepsBoundaryView();
        test.alterViewAsSelectProducesFormalLineage();
        test.createTableAsWithSelectProducesColumnLineage();
        test.temporaryTableColumnLineageIsExpandedToFinalTarget();
        test.viewColumnLineageKeepsDerivedExpressionWhenExpanded();
        test.lateralViewExplodeKeepsTableAndExplodeArgumentColumnLineage();
        test.jdbcMetadataProviderRejectsUnsafeTableName();
    }

    @Test
    public void expressionColumnLineageRecordsSourceColumnsAndExpression() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_001")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert into test_a(a1, a2) select b1 + b2, b3 from test_b;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().size(), "statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status");
        TestSupport.assertTrue(statement.isFormalLineageProduced(), "formal lineage produced");
        TestSupport.assertEquals(1, statement.getTableLineages().size(), "table lineage count");
        TestSupport.assertEquals("test_b", statement.getTableLineages().get(0).getSourceObjects().get(0).getQualifiedName(), "source table");
        TestSupport.assertEquals("test_a", statement.getTableLineages().get(0).getTargetObject().getQualifiedName(), "target table");

        List<ColumnLineage> columns = statement.getColumnLineages();
        TestSupport.assertEquals(2, columns.size(), "column lineage count");

        ColumnLineage a1 = columns.get(0);
        TestSupport.assertEquals("test_a.a1", a1.getTargetColumn().getQualifiedName(), "a1 target");
        TestSupport.assertEquals(LineageType.DERIVED, a1.getLineageType(), "a1 lineage type");
        TestSupport.assertEquals("b1 + b2", a1.getExpression(), "a1 expression");
        TestSupport.assertTrue(a1.isExpressionExtracted(), "a1 expression extracted");
        TestSupport.assertEquals("test_b.b1", a1.getSourceColumns().get(0).getQualifiedName(), "a1 first source column");
        TestSupport.assertEquals("test_b.b2", a1.getSourceColumns().get(1).getQualifiedName(), "a1 second source column");

        ColumnLineage a2 = columns.get(1);
        TestSupport.assertEquals("test_a.a2", a2.getTargetColumn().getQualifiedName(), "a2 target");
        TestSupport.assertEquals(LineageType.DIRECT, a2.getLineageType(), "a2 lineage type");
        TestSupport.assertEquals("b3", a2.getExpression(), "a2 expression");
        TestSupport.assertEquals("test_b.b3", a2.getSourceColumns().get(0).getQualifiedName(), "a2 source column");
    }

    @Test
    public void sessionSetVariableIsAppliedAndIgnoredStatementsAreNotReturned() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_002")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "set bizdate=20260630;\n"
                    + "insert overwrite table test_a select b1 from test_b where dt='${hiveconf:bizdate}';\n"
                    + "select b1 from test_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(3, result.getTotalStatementCount(), "total statement count");
        TestSupport.assertEquals(2, result.getIgnoredStatementCount(), "ignored statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "returned statement count");

        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertTrue(statement.getRawSql().contains("${hiveconf:bizdate}"), "raw sql keeps variable");
        TestSupport.assertTrue(statement.getNormalizedSql().contains("20260630"), "normalized sql replaces variable");
        TestSupport.assertEquals("test_a.b1", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "target column inferred from select");
        TestSupport.assertEquals("test_b.b1", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(), "source column");
    }

    @Test
    public void includeIgnoredStatementsReturnsIgnoredResults() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_003")
            .taskVersion("V2")
            .defaultDatabase("default")
            .rawScript("set bizdate=20260630;\nselect b1 from test_b;")
            .options(LineageAnalyzeOptions.builder()
                .includeIgnoredStatements(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(2, result.getTotalStatementCount(), "total statement count");
        TestSupport.assertEquals(2, result.getIgnoredStatementCount(), "ignored statement count");
        TestSupport.assertEquals(2, result.getStatements().size(), "returned ignored statement count");

        StatementLineageResult setStatement = result.getStatements().get(0);
        TestSupport.assertEquals(3, setStatement.getParseStatusCode(), "set status");
        TestSupport.assertEquals("SET", setStatement.getStatementType(), "set statement type");
        TestSupport.assertEquals("SESSION_SET", setStatement.getSkipReason(), "set skip reason");
        TestSupport.assertTrue(setStatement.getRawSql().contains("set bizdate"), "set raw sql preserved");

        StatementLineageResult selectStatement = result.getStatements().get(1);
        TestSupport.assertEquals(3, selectStatement.getParseStatusCode(), "select status");
        TestSupport.assertEquals("PURE_QUERY", selectStatement.getSkipReason(), "select skip reason");
        TestSupport.assertTrue(selectStatement.getColumnLineages().isEmpty(), "ignored select has no column lineage");
    }

    @Test
    public void parseModeCanReturnOnlyTableOrOnlyColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest tableOnlyRequest = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_004")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert into test_a(a1) select b1 from test_b;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        StatementLineageResult tableOnly = analyzer.analyzeScript(tableOnlyRequest).getStatements().get(0);
        TestSupport.assertEquals(1, tableOnly.getTableLineages().size(), "table only table lineage count");
        TestSupport.assertTrue(tableOnly.getColumnLineages().isEmpty(), "table only column lineage count");

        LineageAnalyzeRequest columnOnlyRequest = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_005")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert into test_a(a1) select b1 from test_b;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        StatementLineageResult columnOnly = analyzer.analyzeScript(columnOnlyRequest).getStatements().get(0);
        TestSupport.assertTrue(columnOnly.getTableLineages().isEmpty(), "column only table lineage count");
        TestSupport.assertEquals(1, columnOnly.getColumnLineages().size(), "column only column lineage count");
    }

    @Test
    public void failedStatementIsReturnedWithNumericStatusAndRawSql() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_006")
            .taskVersion("V3")
            .defaultDatabase("default")
            .rawScript("insert into test_a select from;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getFailedStatementCount(), "failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "returned failed statement count");

        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(2, statement.getParseStatusCode(), "failed status");
        TestSupport.assertEquals("SQL_PARSE_ERROR", statement.getFailureCode(), "failure code");
        TestSupport.assertEquals("insert into test_a select from", statement.getRawSql(), "failed raw sql");
        TestSupport.assertTrue(statement.getTableLineages().isEmpty(), "failed table lineage");
        TestSupport.assertTrue(statement.getColumnLineages().isEmpty(), "failed column lineage");
    }

    @Test
    public void columnLineageUsesMetadataCacheToInferTargetColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        CountingMetadataProvider metadataProvider = new CountingMetadataProvider(
            HiveTableMetadata.table(TableIdentifier.of("default", "default", "test_a"), Collections.singletonList("a1"))
        );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_007")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert into test_a select b1 from test_b;\n"
                    + "insert into test_a select b2 from test_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(2, result.getStatements().size(), "statement count");
        TestSupport.assertEquals(1, metadataProvider.getLoadCount(), "metadata load count");
        TestSupport.assertEquals("test_a.a1", result.getStatements().get(0).getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "first target");
        TestSupport.assertEquals("test_a.a1", result.getStatements().get(1).getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "second target");
    }

    @Test
    public void tableOnlyModeDoesNotLoadMetadata() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        CountingMetadataProvider metadataProvider = new CountingMetadataProvider(
            HiveTableMetadata.table(TableIdentifier.of("default", "default", "test_a"), Collections.singletonList("a1"))
        );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_008")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert into test_a select b1 from test_b;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().get(0).getTableLineages().size(), "table lineage count");
        TestSupport.assertEquals(0, metadataProvider.getLoadCount(), "metadata load count");
    }

    @Test
    public void unresolvedVariablesAreReplacedByFallbackConstant() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_009")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert into test_a(a1) select b1 from test_b where dt='${missing_bizdate}';")
            .options(LineageAnalyzeOptions.builder()
                .fallbackVariableValue("19700101")
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertTrue(statement.getRawSql().contains("${missing_bizdate}"), "raw sql keeps unresolved variable");
        TestSupport.assertTrue(statement.getNormalizedSql().contains("19700101"), "normalized sql uses fallback constant");
        TestSupport.assertTrue(!statement.getNormalizedSql().contains("${"), "normalized sql has no unresolved marker");
        assertContainsWarning(statement.getWarnings(), WarningCode.UNRESOLVED_VARIABLE);
    }

    @Test
    public void hivevarAndEnvironmentVariablesAreNormalized() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_010")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "SET hivevar:VARI = 'xx';\n"
                    + "insert into test_a(a1) select name from stu where age = ${hivevar:VARI} and env = '@{run_env}';"
            )
            .options(LineageAnalyzeOptions.builder()
                .fallbackVariableValue("fallback_value")
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertTrue(statement.getRawSql().contains("${hivevar:VARI}"), "raw sql keeps hivevar");
        TestSupport.assertTrue(statement.getRawSql().contains("@{run_env}"), "raw sql keeps env variable");
        TestSupport.assertTrue(statement.getNormalizedSql().contains("xx"), "normalized sql replaces hivevar");
        TestSupport.assertTrue(statement.getNormalizedSql().contains("fallback_value"), "normalized sql replaces env variable");
        TestSupport.assertTrue(!statement.getNormalizedSql().contains("@{"), "normalized sql has no env marker");
    }

    @Test
    public void productionSample01ExtractsJoinedSourceTables() throws Exception {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_sample")
            .taskId("samplingHql01")
            .taskVersion("V1")
            .defaultDatabase("etl_monitor")
            .rawScript(readWorkspaceFile("samplingHql01.txt"))
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "sample01 failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "sample01 lineage statement count");

        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals("etl_monitor.meta_mid_bdp_usr_login_record", statement.getOutputObject().getQualifiedName(), "sample01 target");
        assertContainsSource(statement, "etl_monitor.bdp_meta_leviathan_t_account_login_record");
        assertContainsSource(statement, "etl_monitor.meta_mid_bdp_usr_info");
    }

    @Test
    public void productionSample02ExtractsUnionAndJoinSourceTables() throws Exception {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_sample")
            .taskId("samplingHql02")
            .taskVersion("V1")
            .defaultDatabase("etl_monitor")
            .rawScript(readWorkspaceFile("samplingHql02.txt"))
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "sample02 failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "sample02 lineage statement count");

        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals("etl_monitor.meta_mid_bdp_user_dashboard_pms_group", statement.getOutputObject().getQualifiedName(), "sample02 target");
        assertContainsSource(statement, "etl_monitor.bdp_meta_mcmeta_online_share_dash");
        assertContainsSource(statement, "etl_monitor.meta_mid_bdp_usr_info");
        assertContainsSource(statement, "etl_monitor.bdp_meta_mcmeta_online_dashboard");
        assertContainsSource(statement, "etl_monitor.bdp_meta_mcmeta_online_project");
        assertContainsSource(statement, "etl_monitor.bdp_meta_leviathan_t_account");
        assertContainsSource(statement, "etl_monitor.meta_mid_bdp_grp_usr_map");
    }

    @Test
    public void generatedCoreLineageCasesParseWithoutFailure() throws Exception {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_generated")
            .taskId("core_lineage_cases")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(readWorkspaceFile("src/test/resources/hql/generated/core_lineage_cases.sql"))
            .options(LineageAnalyzeOptions.builder()
                .fallbackVariableValue("fallback_value")
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "generated failed statement count");
        TestSupport.assertEquals(3, result.getStatements().size(), "generated lineage statement count");
        assertContainsSource(result.getStatements().get(1), "ods.user_base");
        assertContainsSource(result.getStatements().get(1), "ods.user_dept");
        assertContainsSource(result.getStatements().get(2), "ods.union_source_a");
        assertContainsSource(result.getStatements().get(2), "ods.union_source_b");
    }

    @Test
    public void unionAllColumnLineageUsesBranchSelectColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.union_source_a", "id", "name")
            .addTable("ods.union_source_b", "id", "name")
            .addTable("dwd.union_target", "id", "name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_union")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.union_target "
                    + "select id, name from ods.union_source_a "
                    + "union all "
                    + "select id, name from ods.union_source_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "union parse status");
        TestSupport.assertEquals(2, statement.getColumnLineages().size(), "union column lineage count");
        TestSupport.assertEquals("dwd.union_target.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "union id target");
        TestSupport.assertEquals("union_all(ods.union_source_a.id, ods.union_source_b.id)", statement.getColumnLineages().get(0).getExpression(), "union id expression");
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "ods.union_source_a.id");
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "ods.union_source_b.id");
        TestSupport.assertEquals("dwd.union_target.name", statement.getColumnLineages().get(1).getTargetColumn().getQualifiedName(), "union name target");
        TestSupport.assertEquals("union_all(ods.union_source_a.name, ods.union_source_b.name)", statement.getColumnLineages().get(1).getExpression(), "union name expression");
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.union_source_a.name");
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.union_source_b.name");
    }

    @Test
    public void constantSelectItemProducesConstantLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_constant")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite table dwd.constant_target(a1) select 't1' as a1 from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        ColumnLineage column = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(0);

        TestSupport.assertEquals("dwd.constant_target.a1", column.getTargetColumn().getQualifiedName(), "constant target");
        TestSupport.assertEquals(LineageType.CONSTANT, column.getLineageType(), "constant lineage type");
        TestSupport.assertEquals("constant('t1')", column.getExpression(), "constant expression");
        TestSupport.assertTrue(column.getSourceColumns().isEmpty(), "constant source columns");
    }

    @Test
    public void unionAllConstantExpressionsAreMerged() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_union_constant")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert overwrite table dwd.union_constant_target(type_name) "
                    + "select 'A' as type_name from ods.union_source_a "
                    + "union all "
                    + "select 'B' as type_name from ods.union_source_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        ColumnLineage column = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(0);

        TestSupport.assertEquals("dwd.union_constant_target.type_name", column.getTargetColumn().getQualifiedName(), "union constant target");
        TestSupport.assertEquals(LineageType.CONSTANT, column.getLineageType(), "union constant lineage type");
        TestSupport.assertEquals("union_all(constant('A'), constant('B'))", column.getExpression(), "union constant expression");
        TestSupport.assertTrue(column.getSourceColumns().isEmpty(), "union constant source columns");
    }

    @Test
    public void columnLineageResolvesJoinAliasToRealSourceTables() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_011")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert into dwd.user_profile(user_id, dept_name) "
                    + "select a.user_id, b.dept_name "
                    + "from ods.user_base a "
                    + "left join ods.user_dept b on a.dept_id = b.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "alias a source column");
        TestSupport.assertEquals("ods.user_dept.dept_name", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "alias b source column");
    }

    @Test
    public void columnLineageUsesSourceMetadataForUnqualifiedJoinColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "dept_id")
            .addTable("ods.user_dept", "dept_id", "dept_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_012")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert into dwd.user_profile(user_id, dept_name) "
                    + "select user_id, dept_name "
                    + "from ods.user_base a "
                    + "left join ods.user_dept b on a.dept_id = b.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "metadata user_id source column");
        TestSupport.assertEquals("ods.user_dept.dept_name", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "metadata dept_name source column");
        TestSupport.assertEquals(2, metadataProvider.getLoadCount(), "source metadata load count");
    }

    @Test
    public void createTableAsSelectProducesFormalTableLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_013")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("create table dwd.user_tmp as select user_id, user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "ctas failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "ctas statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals("CREATE_TABLE_AS_SELECT", statement.getStatementType(), "ctas statement type");
        TestSupport.assertEquals("dwd.user_tmp", statement.getOutputObject().getQualifiedName(), "ctas target");
        assertContainsSource(statement, "ods.user_base");
    }

    @Test
    public void temporaryTableLineageIsExpandedToFinalTargetByDefault() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_014")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "create temporary table tmp_user as select user_id from ods.user_base;\n"
                    + "insert overwrite table dwd.user_profile select user_id from tmp_user;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "temporary failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "temporary returned statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName(), "temporary final target");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertTrue(statement.getTableLineages().get(0).isThroughTemporaryTable(), "temporary lineage marker");
    }

    @Test
    public void includeTemporaryLineageReturnsTemporaryCreateStatement() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_015")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "create temporary table tmp_user as select user_id from ods.user_base;\n"
                    + "insert overwrite table dwd.user_profile select user_id from tmp_user;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .includeTemporaryLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(2, result.getStatements().size(), "include temporary statement count");
        TestSupport.assertEquals("CREATE_TEMPORARY_TABLE_AS_SELECT", result.getStatements().get(0).getStatementType(), "temporary statement type");
        TestSupport.assertEquals("tmp_user", result.getStatements().get(0).getOutputObject().getQualifiedName(), "temporary table target");
        assertContainsSource(result.getStatements().get(0), "ods.user_base");
        assertContainsSource(result.getStatements().get(1), "ods.user_base");
    }

    @Test
    public void hiveParserAstAdapterParsesInsertJoinFromAst() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "insert overwrite table dwd.user_profile "
                + "select a.user_id, b.dept_name "
                + "from ods.user_base a "
                + "left join ods.user_dept b on a.dept_id = b.dept_id"
        );

        TestSupport.assertEquals(StatementType.INSERT_SELECT, statement.getStatementType(), "ast insert statement type");
        TestSupport.assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName(), "ast insert target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
        assertContainsSource(statement.getInputObjects(), "ods.user_dept");
    }

    @Test
    public void hiveParserAstAdapterParsesCreateTableAsSelectFromAst() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "create table dwd.user_tmp as select user_id from ods.user_base"
        );

        TestSupport.assertEquals(StatementType.CREATE_TABLE_AS_SELECT, statement.getStatementType(), "ast ctas statement type");
        TestSupport.assertEquals("dwd.user_tmp", statement.getOutputObject().getQualifiedName(), "ast ctas target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
    }

    @Test
    public void hiveParserAstAdapterParsesAlterViewAsSelectFromAst() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "alter view dim.v_user as select user_id, upper(user_name) as user_name from ods.user_base"
        );

        TestSupport.assertEquals(StatementType.ALTER_VIEW_AS_SELECT, statement.getStatementType(), "ast alter view statement type");
        TestSupport.assertEquals("dim.v_user", statement.getOutputObject().getQualifiedName(), "ast alter view target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
        TestSupport.assertEquals("user_id", statement.getTargetColumns().get(0), "ast alter view first target column");
        TestSupport.assertEquals("user_name", statement.getTargetColumns().get(1), "ast alter view second target column");
    }

    @Test
    public void fallbackParserAdapterUsesHeuristicWhenPrimaryFails() {
        HiveAstParserAdapter adapter = new FallbackHiveAstParserAdapter(
            new HiveAstParserAdapter() {
                @Override
                public ParsedHiveStatement parse(String normalizedSql) {
                    throw new IllegalArgumentException("primary failed");
                }
            },
            new HeuristicHiveAstParserAdapter()
        );

        ParsedHiveStatement statement = adapter.parse(
            "insert overwrite table dwd.user_profile "
                + "select user_id "
                + "from ods.user_base"
        );

        TestSupport.assertEquals(StatementType.INSERT_SELECT, statement.getStatementType(), "fallback statement type");
        TestSupport.assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName(), "fallback target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
    }

    @Test
    public void viewInputIsExpandedAndViewMetadataIsCached() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView(
                "dim.v_user_base",
                "select user_id, user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_016")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.user_profile select user_id from dim.v_user_base;\n"
                    + "insert overwrite table dwd.user_profile_daily select user_name from dim.v_user_base;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .expandViewLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "view failed statement count");
        TestSupport.assertEquals(2, result.getStatements().size(), "view statement count");
        assertContainsSource(result.getStatements().get(0), "ods.user_base");
        assertContainsSource(result.getStatements().get(1), "ods.user_base");
        TestSupport.assertEquals(2, metadataProvider.getLoadCount(), "view and base metadata should be cached");
    }

    @Test
    public void commonTableExpressionUsesRealSourcesAndColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.user_profile", "user_id", "dept_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_017")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "with t as (select user_id, dept_id from ods.user_base) "
                    + "insert overwrite table dwd.user_profile "
                    + "select t.user_id, d.dept_name "
                    + "from t join ods.user_dept d on t.dept_id = d.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "cte parse status");
        assertContainsSource(statement, "ods.user_base");
        assertContainsSource(statement, "ods.user_dept");
        assertNotContainsSource(statement, "t");
        TestSupport.assertEquals("dwd.user_profile.user_id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "cte first target");
        TestSupport.assertEquals("ods.user_base.user_id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(), "cte first source");
        TestSupport.assertEquals("ods.user_dept.dept_name", statement.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName(), "cte second source");
    }

    @Test
    public void createViewAsSelectProducesFormalLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_018")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("create view dim.v_user as select user_id, concat(first_name, last_name) as user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "view parse status");
        TestSupport.assertEquals("CREATE_VIEW_AS_SELECT", statement.getStatementType(), "view statement type");
        TestSupport.assertEquals("dim.v_user", statement.getOutputObject().getQualifiedName(), "view target");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals("dim.v_user.user_id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "view first target");
        TestSupport.assertEquals(LineageType.DERIVED, statement.getColumnLineages().get(1).getLineageType(), "view derived target");
        TestSupport.assertEquals("concat(first_name, last_name)", statement.getColumnLineages().get(1).getExpression(), "view expression");
    }

    @Test
    public void viewColumnLineageExpandsToBaseColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView(
                "dim.v_user_base",
                "select user_id, concat(first_name, last_name) as user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_019")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile(user_id, user_name) select user_id, user_name from dim.v_user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .expandViewLineage(true)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "view direct source");
        assertContainsColumn(columns.get(1).getSourceColumns(), "ods.user_base.first_name");
        assertContainsColumn(columns.get(1).getSourceColumns(), "ods.user_base.last_name");
    }

    @Test
    public void dynamicPartitionColumnLineageUsesSelectField() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_020")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite table dwd.user_profile partition(dt) select user_id, dt from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("dwd.user_profile.dt", columns.get(1).getTargetColumn().getQualifiedName(), "dynamic partition target");
        TestSupport.assertEquals("ods.user_base.dt", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "dynamic partition source");
    }

    @Test
    public void staticPartitionColumnLineageRecordsConstantExpression() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_021")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite table dwd.user_profile partition(dt='20260701') select user_id from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(2, columns.size(), "static partition lineage count");
        TestSupport.assertEquals("dwd.user_profile.dt", columns.get(1).getTargetColumn().getQualifiedName(), "static partition target");
        TestSupport.assertEquals("constant('20260701')", columns.get(1).getExpression(), "static partition expression");
        TestSupport.assertTrue(columns.get(1).getSourceColumns().isEmpty(), "static partition source columns");
        TestSupport.assertEquals(LineageType.CONSTANT, columns.get(1).getLineageType(), "static partition lineage type");
    }

    @Test
    public void mixedStaticAndDynamicPartitionColumnsAreAligned() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.user_tag_detail", "user_id", "tag", "source_system", "dt")
            .addTable("ods.user_base", "user_id", "tags", "dt");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_partition")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.user_tag_detail partition(source_system='profile', dt) "
                    + "select user_id, tag, dt "
                    + "from ods.user_base lateral view explode(tags) exploded_tags as tag;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("dwd.user_tag_detail.dt", columns.get(2).getTargetColumn().getQualifiedName(), "dynamic partition target");
        TestSupport.assertEquals("ods.user_base.dt", columns.get(2).getSourceColumns().get(0).getQualifiedName(), "dynamic partition source");
        TestSupport.assertEquals("dwd.user_tag_detail.source_system", columns.get(3).getTargetColumn().getQualifiedName(), "static partition target");
        TestSupport.assertEquals(LineageType.CONSTANT, columns.get(3).getLineageType(), "static partition type");
        TestSupport.assertEquals("constant('profile')", columns.get(3).getExpression(), "static partition expression");
    }

    @Test
    public void statementSplitterIgnoresSemicolonInsideStringAndComments() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_022")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "-- comment ; should not split\n"
                    + "insert overwrite table dwd.string_case select 'a;b' as txt from ods.user_base;\n"
                    + "/* block ; comment */\n"
                    + "insert overwrite table dwd.next_case select user_id from ods.user_base;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "comment splitter failed count");
        TestSupport.assertEquals(2, result.getStatements().size(), "comment splitter statement count");
        TestSupport.assertEquals("dwd.string_case", result.getStatements().get(0).getOutputObject().getQualifiedName(), "first target after splitter");
        TestSupport.assertEquals("dwd.next_case", result.getStatements().get(1).getOutputObject().getQualifiedName(), "second target after splitter");
    }

    @Test
    public void insertOverwriteDirectoryIsIgnoredBecauseThereIsNoOutputTable() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_023")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite directory '/tmp/a' select user_id from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build());

        TestSupport.assertEquals(1, result.getIgnoredStatementCount(), "directory insert ignored count");
        TestSupport.assertEquals(0, result.getFailedStatementCount(), "directory insert failed count");
        TestSupport.assertTrue(result.getStatements().isEmpty(), "directory insert returned statements");
    }

    @Test
    public void selectStarExpandsColumnsFromMetadata() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name")
            .addTable("dwd.user_profile", "user_id", "user_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_024")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile select * from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(2, columns.size(), "select star column count");
        TestSupport.assertEquals("dwd.user_profile.user_id", columns.get(0).getTargetColumn().getQualifiedName(), "select star first target");
        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "select star first source");
        TestSupport.assertEquals("dwd.user_profile.user_name", columns.get(1).getTargetColumn().getQualifiedName(), "select star second target");
        TestSupport.assertEquals("ods.user_base.user_name", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "select star second source");
    }

    @Test
    public void selectStarAlignsToTargetMetadataWhenSourceHasExtraColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "id", "name", "user_name", "dept_id", "tags", "dt", "amount", "status")
            .addTable("dwd.user_base_snapshot", "user_id", "user_name", "dept_id", "status", "target_only", "tags", "dt");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_star_target")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_base_snapshot select * from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(6, columns.size(), "target metadata select star column count");
        TestSupport.assertEquals("dwd.user_base_snapshot.user_id", columns.get(0).getTargetColumn().getQualifiedName(), "target star user_id");
        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "source star user_id");
        TestSupport.assertEquals("dwd.user_base_snapshot.user_name", columns.get(1).getTargetColumn().getQualifiedName(), "target star user_name");
        TestSupport.assertEquals("ods.user_base.user_name", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "source star user_name");
        TestSupport.assertEquals("dwd.user_base_snapshot.status", columns.get(3).getTargetColumn().getQualifiedName(), "target star status");
        TestSupport.assertEquals("ods.user_base.status", columns.get(3).getSourceColumns().get(0).getQualifiedName(), "source star status");
        TestSupport.assertEquals("dwd.user_base_snapshot.tags", columns.get(4).getTargetColumn().getQualifiedName(), "target star tags");
        TestSupport.assertEquals("ods.user_base.tags", columns.get(4).getSourceColumns().get(0).getQualifiedName(), "source star tags");
        TestSupport.assertEquals("dwd.user_base_snapshot.dt", columns.get(5).getTargetColumn().getQualifiedName(), "target star dt");
        TestSupport.assertEquals("ods.user_base.dt", columns.get(5).getSourceColumns().get(0).getQualifiedName(), "source star dt");
    }

    @Test
    public void subqueryAliasColumnLineageResolvesToBaseTable() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.user_profile", "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_025")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.user_profile "
                    + "select x.user_id "
                    + "from (select user_id from ods.user_base) x;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals("ods.user_base.user_id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(), "subquery source column");
    }

    @Test
    public void metadataCacheExpiresPositiveAndMissingEntries() throws Exception {
        TableIdentifier tableIdentifier = TableIdentifier.of("default", "ods", "user_base");
        ExpiringMetadataProvider positiveProvider = new ExpiringMetadataProvider(
            null,
            HiveTableMetadata.table(tableIdentifier, Collections.singletonList("user_id"))
        );
        HiveMetadataCache positiveCache = new HiveMetadataCache(1L);

        TestSupport.assertEquals(null, positiveCache.getTableMetadata(tableIdentifier, positiveProvider), "initial missing metadata");
        Thread.sleep(5L);
        HiveTableMetadata loadedAfterMissingExpiry = positiveCache.getTableMetadata(tableIdentifier, positiveProvider);

        TestSupport.assertEquals(2, positiveProvider.getLoadCount(), "missing ttl reload count");
        TestSupport.assertEquals("user_id", loadedAfterMissingExpiry.getColumns().get(0), "missing ttl reloaded column");

        ExpiringMetadataProvider changedProvider = new ExpiringMetadataProvider(
            HiveTableMetadata.table(tableIdentifier, Collections.singletonList("old_col")),
            HiveTableMetadata.table(tableIdentifier, Collections.singletonList("new_col"))
        );
        HiveMetadataCache positiveTtlCache = new HiveMetadataCache(1L);

        TestSupport.assertEquals("old_col", positiveTtlCache.getTableMetadata(tableIdentifier, changedProvider).getColumns().get(0), "initial positive metadata");
        Thread.sleep(5L);
        TestSupport.assertEquals("new_col", positiveTtlCache.getTableMetadata(tableIdentifier, changedProvider).getColumns().get(0), "positive ttl reloaded metadata");
        TestSupport.assertEquals(2, changedProvider.getLoadCount(), "positive ttl reload count");
    }

    @Test
    public void missingTargetMetadataEmitsWarningsOnStatementAndAnalyzeResult() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_026")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.missing_target select user_id from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);
        StatementLineageResult statement = result.getStatements().get(0);

        assertContainsWarning(statement.getWarnings(), WarningCode.METADATA_NOT_FOUND);
        assertContainsWarning(result.getWarnings(), WarningCode.METADATA_NOT_FOUND);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "missing metadata should not fail statement");
    }

    @Test
    public void missingViewDefinitionEmitsWarningAndKeepsViewSource() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView("dim.v_user_base", null, "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_027")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile select user_id from dim.v_user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "dim.v_user_base");
        assertContainsWarning(statement.getWarnings(), WarningCode.VIEW_METADATA_NOT_FOUND);
    }

    @Test
    public void viewExpansionDepthExceededEmitsWarningAndKeepsBoundaryView() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        TestMetadataProvider metadataProvider = new TestMetadataProvider()
            .addView(
                "dim.v_user_level_1",
                "select user_id from dim.v_user_level_2",
                "user_id"
            )
            .addView(
                "dim.v_user_level_2",
                "select user_id from ods.user_base",
                "user_id"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_028")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile(user_id) select user_id from dim.v_user_level_1;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .maxViewExpandDepth(1)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "dim.v_user_level_2");
        assertContainsWarning(statement.getWarnings(), WarningCode.VIEW_EXPANSION_DEPTH_EXCEEDED);
    }

    @Test
    public void alterViewAsSelectProducesFormalLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_028")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("alter view dim.v_user as select user_id, upper(user_name) as user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "alter view parse status");
        TestSupport.assertEquals("ALTER_VIEW_AS_SELECT", statement.getStatementType(), "alter view statement type");
        TestSupport.assertEquals("dim.v_user", statement.getOutputObject().getQualifiedName(), "alter view target");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals("dim.v_user.user_id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "alter view first target");
        TestSupport.assertEquals("ods.user_base.user_id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(), "alter view direct source");
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.user_base.user_name");
    }

    @Test
    public void createTableAsWithSelectProducesColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.order_detail", "user_id", "order_id", "amount", "pay_time", "pay_status", "dt")
            .addTable("dwd.user_order_summary", "user_id", "total_amount", "order_cnt", "last_pay_time");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_cte_ctas")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "create table if not exists dwd.user_order_summary stored as parquet as "
                    + "with paid_orders as ("
                    + "select user_id, order_id, amount, pay_time from ods.order_detail where pay_status = 'PAID'"
                    + ") "
                    + "select user_id, sum(amount) as total_amount, count(*) as order_cnt, max(pay_time) as last_pay_time "
                    + "from paid_orders group by user_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals("CREATE_TABLE_AS_SELECT", statement.getStatementType(), "ctas with cte statement type");
        assertContainsSource(statement, "ods.order_detail");
        List<ColumnLineage> columns = statement.getColumnLineages();
        TestSupport.assertEquals("dwd.user_order_summary.user_id", columns.get(0).getTargetColumn().getQualifiedName(), "ctas cte user_id target");
        TestSupport.assertEquals("ods.order_detail.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "ctas cte user_id source");
        TestSupport.assertEquals(LineageType.DIRECT, columns.get(0).getLineageType(), "ctas cte user_id type");
        TestSupport.assertEquals("ods.order_detail.amount", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "ctas cte total amount source");
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(1).getLineageType(), "ctas cte total amount type");
        TestSupport.assertEquals("sum(amount)", columns.get(1).getExpression(), "ctas cte total amount expression");
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(2).getLineageType(), "ctas cte count type");
        TestSupport.assertEquals("count(*)", columns.get(2).getExpression(), "ctas cte count expression");
        TestSupport.assertEquals("ods.order_detail.pay_time", columns.get(3).getSourceColumns().get(0).getQualifiedName(), "ctas cte last pay time source");
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(3).getLineageType(), "ctas cte last pay time type");
    }

    @Test
    public void temporaryTableColumnLineageIsExpandedToFinalTarget() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name", "dept_id", "status", "dt")
            .addTable("ods.user_dept", "dept_id", "dept_name")
            .addTable("dwd.user_profile_enriched", "user_id", "user_name", "dept_name", "user_status", "process_date");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_temp_columns")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "create temporary table tmp_user_profile as "
                    + "select u.user_id, u.user_name, u.dept_id, d.dept_name, "
                    + "case when u.status = 1 then 'ACTIVE' else 'INACTIVE' end as user_status "
                    + "from ods.user_base u left join ods.user_dept d on u.dept_id = d.dept_id; "
                    + "insert overwrite table dwd.user_profile_enriched(user_id, user_name, dept_name, user_status, process_date) "
                    + "select user_id, user_name, dept_name, user_status, '20260701' as process_date from tmp_user_profile;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .includeTemporaryLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);
        StatementLineageResult temporaryStatement = result.getStatements().get(0);
        StatementLineageResult finalStatement = result.getStatements().get(1);

        TestSupport.assertEquals("tmp_user_profile.user_id", temporaryStatement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "temporary user_id target");
        TestSupport.assertEquals("tmp_user_profile.dept_name", temporaryStatement.getColumnLineages().get(3).getTargetColumn().getQualifiedName(), "temporary dept_name target");
        ColumnLineage userStatus = finalStatement.getColumnLineages().get(3);
        TestSupport.assertEquals("dwd.user_profile_enriched.user_status", userStatus.getTargetColumn().getQualifiedName(), "final user_status target");
        TestSupport.assertEquals("ods.user_base.status", userStatus.getSourceColumns().get(0).getQualifiedName(), "final user_status source");
        TestSupport.assertEquals(LineageType.DERIVED, userStatus.getLineageType(), "final user_status type");
        TestSupport.assertEquals("case when u.status = 1 then 'ACTIVE' else 'INACTIVE' end", userStatus.getExpression(), "final user_status expression");
        for (LineageWarning warning : temporaryStatement.getWarnings()) {
            TestSupport.assertTrue(warning.getMessage().indexOf("tmp_user_profile") < 0, "temporary table should not emit metadata warning");
        }
    }

    @Test
    public void viewColumnLineageKeepsDerivedExpressionWhenExpanded() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name")
            .addTable("dwd.active_user_profile", "user_id", "user_name")
            .addView(
                "dim.v_user_profile",
                "select user_id, upper(user_name) as user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_view_columns")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.active_user_profile(user_id, user_name) select user_id, user_name from dim.v_user_profile;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        ColumnLineage userName = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(1);

        TestSupport.assertEquals("ods.user_base.user_name", userName.getSourceColumns().get(0).getQualifiedName(), "expanded view user_name source");
        TestSupport.assertEquals(LineageType.DERIVED, userName.getLineageType(), "expanded view user_name type");
        TestSupport.assertEquals("upper(user_name)", userName.getExpression(), "expanded view user_name expression");
    }

    @Test
    public void lateralViewExplodeKeepsTableAndExplodeArgumentColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_029")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert overwrite table dwd.user_tags(user_id, tag) "
                    + "select user_id, tag "
                    + "from ods.user_base lateral view explode(tags) exploded_tags as tag;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "lateral view parse status");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals("ods.user_base.user_id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(), "ordinary lateral source");
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.user_base.tags");
        TestSupport.assertEquals(LineageType.DERIVED, statement.getColumnLineages().get(1).getLineageType(), "lateral explode tag type");
        TestSupport.assertEquals("explode(tags)", statement.getColumnLineages().get(1).getExpression(), "lateral explode tag expression");
    }

    @Test
    public void jdbcMetadataProviderRejectsUnsafeTableName() {
        try {
            new JdbcMetadataColumnV2Provider(
                "jdbc:mysql://127.0.0.1:3306/hive",
                "root",
                "85418825",
                "metadata_column_v2 where 1=1"
            );
            throw new AssertionError("Expected unsafe metadata table name to be rejected");
        } catch (IllegalArgumentException expected) {
            TestSupport.assertTrue(
                expected.getMessage().contains("metadata table name contains illegal characters"),
                "unsafe metadata table name error message"
            );
        }
    }

    private static String readWorkspaceFile(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void assertContainsSource(List<DataObjectRef> inputObjects, String qualifiedName) {
        StringBuilder actualSources = new StringBuilder();
        for (DataObjectRef sourceObject : inputObjects) {
            if (actualSources.length() > 0) {
                actualSources.append(", ");
            }
            actualSources.append(sourceObject.getQualifiedName());
            if (qualifiedName.equals(sourceObject.getQualifiedName())) {
                return;
            }
        }
        throw new AssertionError("Expected source object " + qualifiedName + ", actual: [" + actualSources + "]");
    }

    private static void assertContainsSource(StatementLineageResult statement, String qualifiedName) {
        assertContainsSource(statement.getInputObjects(), qualifiedName);
    }

    private static void assertNotContainsSource(StatementLineageResult statement, String qualifiedName) {
        for (DataObjectRef sourceObject : statement.getInputObjects()) {
            if (qualifiedName.equals(sourceObject.getQualifiedName())) {
                throw new AssertionError("Did not expect source object " + qualifiedName);
            }
        }
    }

    private static void assertContainsColumn(List<ColumnRef> columns, String qualifiedName) {
        StringBuilder actualColumns = new StringBuilder();
        for (ColumnRef column : columns) {
            if (actualColumns.length() > 0) {
                actualColumns.append(", ");
            }
            actualColumns.append(column.getQualifiedName());
            if (qualifiedName.equals(column.getQualifiedName())) {
                return;
            }
        }
        throw new AssertionError("Expected source column " + qualifiedName + ", actual: [" + actualColumns + "]");
    }

    private static void assertContainsWarning(List<LineageWarning> warnings, WarningCode warningCode) {
        StringBuilder actualWarnings = new StringBuilder();
        for (LineageWarning warning : warnings) {
            if (actualWarnings.length() > 0) {
                actualWarnings.append(", ");
            }
            actualWarnings.append(warning.getWarningCode());
            if (warningCode == warning.getWarningCode()) {
                return;
            }
        }
        throw new AssertionError("Expected warning " + warningCode + ", actual: [" + actualWarnings + "]");
    }

    private static final class CountingMetadataProvider implements HiveMetadataProvider {
        private final HiveTableMetadata metadata;
        private final AtomicInteger loadCount = new AtomicInteger();

        private CountingMetadataProvider(HiveTableMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
            loadCount.incrementAndGet();
            return metadata;
        }

        private int getLoadCount() {
            return loadCount.get();
        }
    }

    private static final class ExpiringMetadataProvider implements HiveMetadataProvider {
        private final HiveTableMetadata first;
        private final HiveTableMetadata second;
        private final AtomicInteger loadCount = new AtomicInteger();

        private ExpiringMetadataProvider(HiveTableMetadata first, HiveTableMetadata second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
            return loadCount.incrementAndGet() == 1 ? first : second;
        }

        private int getLoadCount() {
            return loadCount.get();
        }
    }

    private static final class MapMetadataProvider implements HiveMetadataProvider {
        private final Map<String, HiveTableMetadata> metadataByQualifiedName = new HashMap<>();
        private final AtomicInteger loadCount = new AtomicInteger();

        private MapMetadataProvider addTable(String qualifiedName, String... columns) {
            String[] parts = qualifiedName.split("\\.");
            metadataByQualifiedName.put(
                qualifiedName,
                HiveTableMetadata.table(
                    TableIdentifier.of("default", parts[0], parts[1]),
                    Arrays.asList(columns)
                )
            );
            return this;
        }

        private MapMetadataProvider addView(String qualifiedName, String viewOriginalSql, String... columns) {
            String[] parts = qualifiedName.split("\\.");
            metadataByQualifiedName.put(
                qualifiedName,
                HiveTableMetadata.view(
                    TableIdentifier.of("default", parts[0], parts[1]),
                    Arrays.asList(columns),
                    viewOriginalSql
                )
            );
            return this;
        }

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier) {
            loadCount.incrementAndGet();
            return metadataByQualifiedName.get(tableIdentifier.getQualifiedName());
        }

        private int getLoadCount() {
            return loadCount.get();
        }
    }
}
