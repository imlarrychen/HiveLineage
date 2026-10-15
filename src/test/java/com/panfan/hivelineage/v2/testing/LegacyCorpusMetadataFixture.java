package com.panfan.hivelineage.v2.testing;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.v2.compare.CorpusCase;
import com.panfan.hivelineage.v2.compare.DefaultGeneratedSqlCorpusLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Independent metadata for replaying the historical SQL corpus through the typed pipeline.
 */
public final class LegacyCorpusMetadataFixture {
    private LegacyCorpusMetadataFixture() {
    }

    public static HiveMetadataProvider provider() {
        return new FixtureProvider()
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
                .addTable(
                        "dwd.user_base_snapshot",
                        "user_id", "id", "name", "user_name", "first_name", "last_name",
                        "dept_id", "dt", "tags", "amount", "status"
                )
                .addTable("dwd.user_order_summary", "user_id", "total_amount", "order_cnt", "last_pay_time")
                .addTable("dwd.user_profile_enriched", "user_id", "user_name", "dept_name", "user_status", "process_date")
                .addTable("dwd.active_user_profile", "user_id", "user_name", "dept_name")
                .addTable("dwd.user_tag_detail", "user_id", "tag", "source_system", "dt")
                .addTable("dwd.user_feature_wide", "user_id", "valid_score", "rn", "user_day_key", "udf_score")
                .addTable("dwd.ignore_me", "id", "name")
                .addTable("dwd.parse_error_target", "user_id")
                .addTable("dwd.parse_recovery_target", "user_id", "user_name")
                .addTable("dwd.temp_shadow_target", "user_id", "user_name")
                .addTable("dwd.inline_subquery_target", "user_id", "order_cnt")
                .addTable("dwd.exists_filter_target", "user_id", "user_name")
                .addTable("dwd.semi_join_target", "user_id", "user_name")
                .addTable("dwd.full_outer_join_target", "user_id", "profile_name", "feature_status")
                .addTable("dwd.union_distinct_complex_target", "user_id", "label", "tag_head", "tag_count")
                .addTable(
                        "etl_monitor.meta_mid_bdp_usr_login_record",
                        "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name",
                        "emp_name", "login_time", "os_type", "ip", "province", "city",
                        "mobile_id", "dw_upd_tm"
                )
                .addTable(
                        "etl_monitor.bdp_meta_leviathan_t_account_login_record",
                        "user_id", "login_time", "os_type", "ip", "province", "city", "mobile_id"
                )
                .addTable("etl_monitor.meta_mid_bdp_usr_info", "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name", "emp_name", "role")
                .addTable(
                        "etl_monitor.meta_mid_bdp_user_dashboard_pms_group",
                        "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name",
                        "emp_name", "user_role", "proj_id", "proj_name", "dsh_id",
                        "dsh_name", "dsh_owner", "dis_type", "dw_upd_tm"
                )
                .addTable("etl_monitor.bdp_meta_mcmeta_online_share_dash", "user_id", "group_id", "dsh_id", "type")
                .addTable("etl_monitor.bdp_meta_mcmeta_online_dashboard", "dsh_id", "proj_id", "name", "user_id", "is_del")
                .addTable("etl_monitor.bdp_meta_mcmeta_online_project", "proj_id", "name")
                .addTable("etl_monitor.bdp_meta_leviathan_t_account", "user_id", "name")
                .addTable(
                        "etl_monitor.meta_mid_bdp_grp_usr_map",
                        "domain", "bbk_name", "dept_ful_nm", "user_id", "user_name",
                        "emp_name", "role", "group_id"
                )
                .addView(
                        "default.tmp_shadow_user",
                        "select id as user_id, name as user_name from ods.union_source_a",
                        "user_id",
                        "user_name"
                )
                .addView(
                        "dim.v_user_base",
                        "select user_id, concat(first_name, last_name) as user_name from ods.user_base",
                        "user_id",
                        "user_name"
                )
                .addView(
                        "dim.v_user_profile",
                        "select u.user_id, upper(u.user_name) as user_name, d.dept_name "
                                + "from ods.user_base u left join ods.user_dept d "
                                + "on u.dept_id = d.dept_id where u.status = 1",
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

    /**
     * Historical comparison cases have their own metadata and expected snapshots.
     */
    public static boolean isLegacyBaseline(Path file) {
        return !file.getFileName().toString().startsWith("v2_");
    }

    public static List<CorpusCase> legacyCorpus() throws IOException {
        return new DefaultGeneratedSqlCorpusLoader().load(Paths.get("src/test/resources/hql/generated")).stream()
                .filter(item -> isLegacyBaseline(Paths.get(item.getRelativePath()))).collect(Collectors.toList());
    }

    private static final class FixtureProvider implements HiveMetadataProvider {
        private final Map<String, HiveTableMetadata> tables = new LinkedHashMap<>();

        private FixtureProvider addTable(String name, String... columns) {
            TableIdentifier table = identifier(name);
            tables.put(table.getQualifiedName(), HiveTableMetadata.table(table, Arrays.asList(columns)));
            return this;
        }

        private FixtureProvider addView(String name, String sql, String... columns) {
            TableIdentifier table = identifier(name);
            tables.put(table.getQualifiedName(), HiveTableMetadata.view(table, Arrays.asList(columns), sql));
            return this;
        }

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier table) {
            return tables.get(table.getQualifiedName());
        }

        private static TableIdentifier identifier(String name) {
            int separator = name.indexOf('.');
            return separator < 0 ? TableIdentifier.of("default", "default", name)
                    : TableIdentifier.of("default", name.substring(0, separator), name.substring(separator + 1));
        }
    }
}
