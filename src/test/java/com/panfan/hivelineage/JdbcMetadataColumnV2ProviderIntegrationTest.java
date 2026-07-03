package com.panfan.hivelineage;

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
        TestSupport.assertEquals("dwd_it.user_profile_it.user_id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "jdbc analyzer first target");
        TestSupport.assertEquals("ods_it.user_base_it.user_id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(), "jdbc analyzer first source");
        TestSupport.assertEquals("dwd_it.user_profile_it.user_name", statement.getColumnLineages().get(1).getTargetColumn().getQualifiedName(), "jdbc analyzer second target");
        TestSupport.assertEquals("ods_it.user_base_it.user_name", statement.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName(), "jdbc analyzer second source");
    }

    private void insertMetadata(String jdbcUrl, String username, String password) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement delete = connection.createStatement();
             PreparedStatement insert = connection.prepareStatement(
                 "insert into metadata_column_v2 "
                     + "(id, db_en_name, tb_en_name, column_name, column_type, integer_idx, is_active) "
                     + "values (?, ?, ?, ?, ?, ?, b'1')"
             )) {
            delete.executeUpdate("delete from metadata_column_v2 where db_en_name in ('ods_it', 'dwd_it') and tb_en_name in ('user_base_it', 'user_profile_it')");
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
        }
    }
}
