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

import static com.panfan.hivelineage.AnalyzerTestSupport.*;

public class HiveScriptProcessingTest {
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
    public void createTemporaryMacroIsIgnoredBecauseItHasNoLineageOutput() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        String script = "create temporary macro CUST_AGE_MC(opn_dt string,bth_dt string) "
            + "if(datediff(opn_dt,bth_dt)>=0,1,0);"
            + "insert overwrite table dwd.user_profile(user_id) select user_id from ods.user_base;";

        LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_macro")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(script)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build());

        TestSupport.assertEquals(2, result.getTotalStatementCount(), "macro total statement count");
        TestSupport.assertEquals(1, result.getIgnoredStatementCount(), "macro ignored statement count");
        TestSupport.assertEquals(0, result.getFailedStatementCount(), "macro failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "macro returned statement count");
        TestSupport.assertEquals("dwd.user_profile", result.getStatements().get(0).getOutputObject().getQualifiedName(), "macro following target");

        LineageAnalyzeResult withIgnored = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_macro")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(script)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .includeIgnoredStatements(true)
                .build())
            .build());

        TestSupport.assertEquals(2, withIgnored.getStatements().size(), "macro include ignored returned statement count");
        TestSupport.assertEquals(3, withIgnored.getStatements().get(0).getParseStatusCode(), "macro ignored status");
        TestSupport.assertEquals("CREATE_TEMPORARY_MACRO", withIgnored.getStatements().get(0).getStatementType(), "macro ignored type");
        TestSupport.assertEquals("CREATE_TEMPORARY_MACRO", withIgnored.getStatements().get(0).getSkipReason(), "macro ignored reason");
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
    public void failedStatementDoesNotStopFollowingStatements() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_parse_recovery")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert into dwd.parse_error_target select from;\n"
                    + "insert overwrite table dwd.parse_recovery_target(user_id) select user_id from ods.user_base;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(2, result.getTotalStatementCount(), "parse recovery total count");
        TestSupport.assertEquals(1, result.getFailedStatementCount(), "parse recovery failed count");
        TestSupport.assertEquals(2, result.getStatements().size(), "parse recovery returned count");
        TestSupport.assertEquals(2, result.getStatements().get(0).getParseStatusCode(), "first statement failed status");
        StatementLineageResult recovered = result.getStatements().get(1);
        TestSupport.assertEquals(0, recovered.getParseStatusCode(), "following statement parse status");
        TestSupport.assertEquals("dwd.parse_recovery_target", recovered.getOutputObject().getQualifiedName(), "following statement target");
        assertContainsSource(recovered, "ods.user_base");
    }

    @Test
    public void parseModeAndIgnoredStatementOptionsWorkInMixedScript() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        String script = "set bizdate=20260701;\n"
            + "select user_id from ods.user_base;\n"
            + "insert overwrite table dwd.user_profile(user_id) select user_id from ods.user_base;";

        LineageAnalyzeResult tableOnly = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_mixed_table_only")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(script)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build());

        TestSupport.assertEquals(3, tableOnly.getTotalStatementCount(), "mixed table only total count");
        TestSupport.assertEquals(2, tableOnly.getIgnoredStatementCount(), "mixed table only ignored count");
        TestSupport.assertEquals(1, tableOnly.getStatements().size(), "mixed table only returned count");
        TestSupport.assertEquals(1, tableOnly.getStatements().get(0).getTableLineages().size(), "mixed table only table lineage");
        TestSupport.assertTrue(tableOnly.getStatements().get(0).getColumnLineages().isEmpty(), "mixed table only column lineage");

        LineageAnalyzeResult columnOnlyWithIgnored = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_mixed_column_only")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(script)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .includeIgnoredStatements(true)
                .build())
            .build());

        TestSupport.assertEquals(3, columnOnlyWithIgnored.getStatements().size(), "mixed include ignored returned count");
        TestSupport.assertEquals(3, columnOnlyWithIgnored.getStatements().get(0).getParseStatusCode(), "mixed set ignored status");
        TestSupport.assertEquals(3, columnOnlyWithIgnored.getStatements().get(1).getParseStatusCode(), "mixed select ignored status");
        StatementLineageResult lineageStatement = columnOnlyWithIgnored.getStatements().get(2);
        TestSupport.assertTrue(lineageStatement.getTableLineages().isEmpty(), "mixed column only table lineage");
        TestSupport.assertEquals(1, lineageStatement.getColumnLineages().size(), "mixed column only column lineage");
    }
}
