package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveObjectType;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.Assume;
import org.junit.Test;

public class JdbcMetadataColumnV2ProviderIntegrationTest {
    @Test
    public void queriesColumnsFromMetadataColumnV2() throws Exception {
        String jdbcUrl = System.getProperty("lineage.mysql.url");
        String username = System.getProperty("lineage.mysql.user");
        String password = System.getProperty("lineage.mysql.password");
        Assume.assumeTrue(jdbcUrl != null && username != null && password != null);

        insertMetadata(jdbcUrl, username, password);

        JdbcMetadataColumnV2Provider provider = new JdbcMetadataColumnV2Provider(jdbcUrl, username, password);
        HiveTableMetadata metadata = provider.getTableMetadata(TableIdentifier.of("default", "ods_it", "user_base_it"));

        TestSupport.assertEquals("user_id", metadata.getColumns().get(0), "first jdbc metadata column");
        TestSupport.assertEquals("user_name", metadata.getColumns().get(1), "second jdbc metadata column");
    }

    @Test
    public void analyzerUsesJdbcMetadataProviderForSelectStar() throws Exception {
        String jdbcUrl = System.getProperty("lineage.mysql.url");
        String username = System.getProperty("lineage.mysql.user");
        String password = System.getProperty("lineage.mysql.password");
        Assume.assumeTrue(jdbcUrl != null && username != null && password != null);

        insertMetadata(jdbcUrl, username, password);

        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_jdbc")
            .taskId("task_jdbc")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(new JdbcMetadataColumnV2Provider(jdbcUrl, username, password))
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd_it.user_profile_it select * from ods_it.user_base_it;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build());

        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(
            "dwd_it.user_profile_it.user_id",
            statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "jdbc analyzer first target"
        );
        TestSupport.assertEquals(
            "ods_it.user_base_it.user_id",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "jdbc analyzer first source"
        );
        TestSupport.assertEquals(
            "dwd_it.user_profile_it.user_name",
            statement.getColumnLineages().get(1).getTargetColumn().getQualifiedName(),
            "jdbc analyzer second target"
        );
        TestSupport.assertEquals(
            "ods_it.user_base_it.user_name",
            statement.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName(),
            "jdbc analyzer second source"
        );
    }

    @Test
    public void queriesViewSourceFromHiveViewParseInfo() throws Exception {
        String jdbcUrl = System.getProperty("lineage.mysql.url");
        String username = System.getProperty("lineage.mysql.user");
        String password = System.getProperty("lineage.mysql.password");
        Assume.assumeTrue(jdbcUrl != null && username != null && password != null);

        insertMetadata(jdbcUrl, username, password);
        insertViewMetadata(jdbcUrl, username, password);

        JdbcMetadataColumnV2Provider provider = new JdbcMetadataColumnV2Provider(jdbcUrl, username, password);
        HiveTableMetadata metadata = provider.getTableMetadata(TableIdentifier.of("default", "dim_it", "v_user_it"));

        TestSupport.assertEquals(HiveObjectType.VIEW, metadata.getObjectType(), "jdbc view object type");
        TestSupport.assertTrue(metadata.getViewOriginalSql().contains("from ods_it.user_base_it"), "jdbc view source sql");
        TestSupport.assertEquals("user_id", metadata.getColumns().get(0), "jdbc view first column");
    }

    @Test
    public void analyzerExpandsJdbcViewSourceFromHiveViewParseInfo() throws Exception {
        String jdbcUrl = System.getProperty("lineage.mysql.url");
        String username = System.getProperty("lineage.mysql.user");
        String password = System.getProperty("lineage.mysql.password");
        Assume.assumeTrue(jdbcUrl != null && username != null && password != null);

        insertMetadata(jdbcUrl, username, password);
        insertViewMetadata(jdbcUrl, username, password);

        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_jdbc")
            .taskId("task_jdbc_view")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(new JdbcMetadataColumnV2Provider(jdbcUrl, username, password))
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd_it.user_profile_it(user_id, user_name) select user_id, user_name from dim_it.v_user_it;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build());

        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "jdbc view analyzer parse status");
        TestSupport.assertEquals("ods_it.user_base_it", statement.getInputObjects().get(0).getQualifiedName(), "jdbc view expanded input");
        TestSupport.assertEquals(
            "ods_it.user_base_it.user_id",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "jdbc view first source"
        );
        TestSupport.assertEquals(
            "ods_it.user_base_it.user_name",
            statement.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName(),
            "jdbc view second source"
        );
    }

    @Test
    public void analyzerUsesJdbcMetadataProviderForUnqualifiedJoinColumns() throws Exception {
        String jdbcUrl = System.getProperty("lineage.mysql.url");
        String username = System.getProperty("lineage.mysql.user");
        String password = System.getProperty("lineage.mysql.password");
        Assume.assumeTrue(jdbcUrl != null && username != null && password != null);

        insertJoinMetadata(jdbcUrl, username, password);

        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_jdbc")
            .taskId("task_jdbc_join")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(new JdbcMetadataColumnV2Provider(jdbcUrl, username, password))
            .metadataCache(new HiveMetadataCache())
            .rawScript("select * from ods_it.join_left_it l join ods_it.join_right_it r on user_id = manager_id;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .parseJoinConditions(true)
                .build())
            .build());

        StatementLineageResult statement = result.getStatements().get(0);
        JoinCondition condition = statement.getJoinConditions().get(0);
        TestSupport.assertEquals("user_id = manager_id", condition.getJoinExpression(), "jdbc join expression");
        TestSupport.assertEquals("ods_it.join_left_it.user_id", condition.getJoinColumns().get(0).getQualifiedName(), "jdbc join first column");
        TestSupport.assertEquals("ods_it.join_right_it.manager_id", condition.getJoinColumns().get(1).getQualifiedName(), "jdbc join second column");
    }

    private void insertMetadata(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement delete = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement(
                 "insert into metadata_column_v2 "
                     + "(id, db_en_name, tb_en_name, column_name, column_type, integer_idx, is_active) "
                     + "values (?, ?, ?, ?, ?, ?, b'1')"
             )) {
            delete.executeUpdate(
                "delete from metadata_column_v2 "
                    + "where db_en_name in ('ods_it', 'dwd_it', 'dim_it') "
                    + "and tb_en_name in ('user_base_it', 'user_profile_it', 'v_user_it')"
            );
            insert.setLong(1, 9000000001L);
            insert.setString(2, "ods_it");
            insert.setString(3, "user_base_it");
            insert.setString(4, "user_id");
            insert.setString(5, "string");
            insert.setInt(6, 1);
            insert.executeUpdate();

            insert.setLong(1, 9000000002L);
            insert.setString(2, "ods_it");
            insert.setString(3, "user_base_it");
            insert.setString(4, "user_name");
            insert.setString(5, "string");
            insert.setInt(6, 2);
            insert.executeUpdate();

            insert.setLong(1, 9000000003L);
            insert.setString(2, "dwd_it");
            insert.setString(3, "user_profile_it");
            insert.setString(4, "user_id");
            insert.setString(5, "string");
            insert.setInt(6, 1);
            insert.executeUpdate();

            insert.setLong(1, 9000000004L);
            insert.setString(2, "dwd_it");
            insert.setString(3, "user_profile_it");
            insert.setString(4, "user_name");
            insert.setString(5, "string");
            insert.setInt(6, 2);
            insert.executeUpdate();

            insert.setLong(1, 9000000005L);
            insert.setString(2, "dim_it");
            insert.setString(3, "v_user_it");
            insert.setString(4, "user_id");
            insert.setString(5, "string");
            insert.setInt(6, 1);
            insert.executeUpdate();

            insert.setLong(1, 9000000006L);
            insert.setString(2, "dim_it");
            insert.setString(3, "v_user_it");
            insert.setString(4, "user_name");
            insert.setString(5, "string");
            insert.setInt(6, 2);
            insert.executeUpdate();
        }
    }

    private void insertViewMetadata(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement delete = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement(
                 "insert into hive_view_parse_info "
                     + "(id, view_tbl_id, view_db_id, view_name, view_source, failed_status) "
                     + "values (?, ?, ?, ?, ?, b'0')"
             )) {
            delete.executeUpdate("delete from hive_view_parse_info where view_name in ('dim_it.v_user_it', 'v_user_it')");
            insert.setLong(1, 9100000001L);
            insert.setLong(2, 9100000101L);
            insert.setLong(3, 9100000201L);
            insert.setString(4, "dim_it.v_user_it");
            insert.setString(5, "ods_it.user_base_it");
            insert.executeUpdate();
        }
    }

    private void insertJoinMetadata(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement delete = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement(
                 "insert into metadata_column_v2 "
                     + "(id, db_en_name, tb_en_name, column_name, column_type, integer_idx, is_active) "
                     + "values (?, ?, ?, ?, ?, ?, b'1')"
             )) {
            delete.executeUpdate("delete from metadata_column_v2 where db_en_name = 'ods_it' and tb_en_name in ('join_left_it', 'join_right_it')");
            insert.setLong(1, 9000000101L);
            insert.setString(2, "ods_it");
            insert.setString(3, "join_left_it");
            insert.setString(4, "user_id");
            insert.setString(5, "string");
            insert.setInt(6, 1);
            insert.executeUpdate();

            insert.setLong(1, 9000000102L);
            insert.setString(2, "ods_it");
            insert.setString(3, "join_left_it");
            insert.setString(4, "user_name");
            insert.setString(5, "string");
            insert.setInt(6, 2);
            insert.executeUpdate();

            insert.setLong(1, 9000000103L);
            insert.setString(2, "ods_it");
            insert.setString(3, "join_right_it");
            insert.setString(4, "manager_id");
            insert.setString(5, "string");
            insert.setInt(6, 1);
            insert.executeUpdate();

            insert.setLong(1, 9000000104L);
            insert.setString(2, "ods_it");
            insert.setString(3, "join_right_it");
            insert.setString(4, "dept_name");
            insert.setString(5, "string");
            insert.setInt(6, 2);
            insert.executeUpdate();
        }
    }
}
