package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import java.util.Collections;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.CountingMetadataProvider;
import static com.panfan.hivelineage.AnalyzerTestSupport.ExpiringMetadataProvider;
import static com.panfan.hivelineage.AnalyzerTestSupport.MapMetadataProvider;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;

public class HiveMetadataLineageTest {
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
        TestSupport.assertEquals(
            "default.test_a.a1",
            result.getStatements().get(0).getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "first target"
        );
        TestSupport.assertEquals(
            "default.test_a.a1",
            result.getStatements().get(1).getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "second target"
        );
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

        TestSupport.assertEquals(
            "old_col",
            positiveTtlCache.getTableMetadata(tableIdentifier, changedProvider).getColumns().get(0),
            "initial positive metadata"
        );
        Thread.sleep(5L);
        TestSupport.assertEquals(
            "new_col",
            positiveTtlCache.getTableMetadata(tableIdentifier, changedProvider).getColumns().get(0),
            "positive ttl reloaded metadata"
        );
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

    @Test
    public void missingMetadataKeepsTableLineageAndEmitsWarning() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_missing_metadata_fallback")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.metadata_missing_target select user_id, user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);
        StatementLineageResult statement = result.getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "missing metadata parse status");
        TestSupport.assertEquals(1, statement.getTableLineages().size(), "missing metadata table lineage count");
        TestSupport.assertEquals(
            "dwd.metadata_missing_target",
            statement.getTableLineages().get(0).getTargetObject().getQualifiedName(),
            "missing metadata target"
        );
        assertContainsSource(statement, "ods.user_base");
        assertContainsWarning(statement.getWarnings(), WarningCode.METADATA_NOT_FOUND);
        assertContainsWarning(result.getWarnings(), WarningCode.METADATA_NOT_FOUND);
    }
}
