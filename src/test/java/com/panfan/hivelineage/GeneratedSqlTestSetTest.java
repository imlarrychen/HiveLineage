package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class GeneratedSqlTestSetTest {
    @Test
    public void generatedSqlTestSetsParseWithoutFailure() throws Exception {
        Path generatedDir = Paths.get("src/test/resources/hql/generated");
        List<Path> sqlFiles = listSqlFiles(generatedDir);
        TestSupport.assertTrue(sqlFiles.size() >= 11, "generated sql file count");
        assertContainsFile(sqlFiles, "core_lineage_cases.sql");
        for (int i = 1; i <= 10; i++) {
            assertContainsFile(sqlFiles, String.format("lineage_case_%02d.sql", i));
        }

        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        TestMetadataProvider metadataProvider = generatedMetadataProvider();
        HiveMetadataCache metadataCache = new HiveMetadataCache();

        for (Path sqlFile : sqlFiles) {
            String script = new String(Files.readAllBytes(sqlFile), StandardCharsets.UTF_8);
            LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
                .workflowId("wf_generated")
                .taskId(sqlFile.getFileName().toString().replace(".sql", ""))
                .taskVersion("V1")
                .defaultDatabase("default")
                .metadataProvider(metadataProvider)
                .metadataCache(metadataCache)
                .rawScript(script)
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .expandViewLineage(true)
                    .includeTemporaryLineage(true)
                    .fallbackVariableValue("fallback_value")
                    .build())
                .build());

            TestSupport.assertEquals(0, result.getFailedStatementCount(), "failed count for " + sqlFile + describeFailures(result));
        }
    }

    private static List<Path> listSqlFiles(Path generatedDir) throws Exception {
        List<Path> sqlFiles = new ArrayList<>();
        Files.walk(generatedDir).forEach(path -> {
            if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".sql")) {
                sqlFiles.add(path);
            }
        });
        Collections.sort(sqlFiles);
        return sqlFiles;
    }

    private static TestMetadataProvider generatedMetadataProvider() {
        return new TestMetadataProvider()
            .addTable("test_b", "b1", "b2", "b3", "dt", "env")
            .addTable("test_a", "a1", "a2", "ds", "env_name")
            .addTable("ods.user_base", "user_id", "id", "name", "user_name", "first_name", "last_name", "dept_id", "dt", "tags", "amount", "status")
            .addTable("ods.user_dept", "dept_id", "dept_name")
            .addTable("ods.union_source_a", "id", "name")
            .addTable("ods.union_source_b", "id", "name")
            .addTable("ods.order_detail", "user_id", "order_id", "amount", "pay_time", "pay_status", "dt")
            .addTable("ods.event_log", "user_id", "event_type", "event_time", "dt")
            .addTable("ods.user_feature", "user_id", "status", "score", "update_time", "dt", "level_code")
            .addTable("dwd.user_profile", "user_id", "dept_name")
            .addTable("dwd.user_profile_daily", "user_id", "user_name", "dt")
            .addTable("dwd.union_target", "id", "name")
            .addTable("dwd.user_tags", "user_id", "tag")
            .addTable("dwd.user_amount", "user_id", "total_amount", "dt")
            .addTable("dwd.user_event", "user_id", "event_day")
            .addTable("dwd.star_target", "user_id", "user_name")
            .addTable("dwd.subquery_target", "user_id", "user_name")
            .addTable("dwd.ctas_like_target", "user_id", "user_name")
            .addTable("ads.final_user", "user_id", "user_name")
            .addTable("ads.audit_user", "user_id", "user_name")
            .addTable("dwd.user_base_snapshot", "user_id", "user_name", "dept_id", "tags", "dt")
            .addTable("dwd.user_order_summary", "user_id", "total_amount", "order_cnt", "last_pay_time")
            .addTable("dwd.user_profile_enriched", "user_id", "user_name", "dept_name", "user_status", "process_date")
            .addTable("dwd.active_user_profile", "user_id", "user_name", "dept_name")
            .addTable("dwd.user_tag_detail", "user_id", "tag", "source_system", "dt")
            .addTable("dwd.user_feature_wide", "user_id", "valid_score", "rn", "user_day_key", "udf_score")
            .addTable("dwd.ignore_me", "id", "name")
            .addTable("dwd.parse_error_target", "user_id")
            .addTable("etl_monitor.meta_mid_bdp_usr_login_record", "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name", "emp_name", "login_time", "os_type", "ip", "province", "city", "mobile_id", "dw_upd_tm")
            .addTable("etl_monitor.bdp_meta_leviathan_t_account_login_record", "user_id", "login_time", "os_type", "ip", "province", "city", "mobile_id")
            .addTable("etl_monitor.meta_mid_bdp_usr_info", "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name", "emp_name", "role")
            .addTable("etl_monitor.meta_mid_bdp_user_dashboard_pms_group", "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name", "emp_name", "user_role", "proj_id", "proj_name", "dsh_id", "dsh_name", "dsh_owner", "dis_type", "dw_upd_tm")
            .addTable("etl_monitor.bdp_meta_mcmeta_online_share_dash", "user_id", "group_id", "dsh_id", "type")
            .addTable("etl_monitor.bdp_meta_mcmeta_online_dashboard", "dsh_id", "proj_id", "name", "user_id", "is_del")
            .addTable("etl_monitor.bdp_meta_mcmeta_online_project", "proj_id", "name")
            .addTable("etl_monitor.bdp_meta_leviathan_t_account", "user_id", "name")
            .addTable("etl_monitor.meta_mid_bdp_grp_usr_map", "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name", "emp_name", "role", "group_id")
            .addView(
                "dim.v_user_base",
                "select user_id, concat(first_name, last_name) as user_name from ods.user_base",
                "user_id",
                "user_name"
            )
            .addView(
                "dim.v_user_profile",
                "select u.user_id, upper(u.user_name) as user_name, d.dept_name from ods.user_base u left join ods.user_dept d on u.dept_id = d.dept_id where u.status = 1",
                "user_id",
                "user_name",
                "dept_name"
            )
            .addView(
                "dim.v_user_deep_1",
                "select user_id, user_name from dim.v_user_deep_2",
                "user_id",
                "user_name"
            )
            .addView(
                "dim.v_user_deep_2",
                "select user_id, user_name from ods.user_base",
                "user_id",
                "user_name"
            );
    }

    private static void assertContainsFile(List<Path> sqlFiles, String fileName) {
        for (Path sqlFile : sqlFiles) {
            if (fileName.equals(sqlFile.getFileName().toString())) {
                return;
            }
        }
        throw new AssertionError("Expected generated SQL file " + fileName);
    }

    private static String describeFailures(LineageAnalyzeResult result) {
        StringBuilder builder = new StringBuilder();
        result.getStatements().forEach(statement -> {
            if (statement.getParseStatusCode() == 2) {
                builder.append(" [")
                    .append(statement.getFailureCode())
                    .append(": ")
                    .append(statement.getFailureMessage())
                    .append(" sql=")
                    .append(statement.getRawSql())
                    .append("]");
            }
        });
        return builder.toString();
    }
}
