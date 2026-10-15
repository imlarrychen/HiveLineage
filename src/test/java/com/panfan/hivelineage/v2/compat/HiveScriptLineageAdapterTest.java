package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.StatementParseStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HiveScriptLineageAdapterTest {
    private static final String SQL = "insert overwrite table dwd.user_profile select * from ods.user_base;";

    @Test
    public void existingRequestAndResultWorkThroughTheCommonAnalyzerInterface() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAdapter();
        MutableMetadataProvider provider = new MutableMetadataProvider();
        HiveMetadataCache cache = new HiveMetadataCache();
        LineageAnalyzeResult result = analyzer.analyzeScript(request(provider, cache, "V1").build());

        assertEquals("wf_001", result.getWorkflowId());
        assertEquals("task_001", result.getTaskId());
        assertEquals("V1", result.getTaskVersion());
        assertEquals(1, result.getTotalStatementCount());
        assertEquals(0, result.getIgnoredStatementCount());
        assertEquals(0, result.getFailedStatementCount());
        assertEquals(1, result.getStatements().size());
        StatementLineageResult statement = result.getStatements().get(0);
        assertEquals("wf_001", statement.getWorkflowId());
        assertEquals("task_001", statement.getTaskId());
        assertEquals("V1", statement.getTaskVersion());
        assertEquals("wf_001_task_001_V1_0", statement.getStatementId());
        assertEquals(Integer.valueOf(StatementParseStatus.SUCCESS.getCode()), statement.getParseStatusCode());
        assertEquals(LineageParseMode.TABLE_AND_COLUMN, statement.getParseMode());
        assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName());
        assertEquals("ods.user_base", statement.getInputObjects().get(0).getQualifiedName());
        assertEquals(1, statement.getTableLineages().size());
        assertColumns(statement, "user_id", "user_name");
        assertEquals(2, provider.loads);
        assertEquals(2, cache.size());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    public void existingParseModesControlTheLegacyResult() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAdapter();
        MutableMetadataProvider provider = new MutableMetadataProvider();
        HiveMetadataCache cache = new HiveMetadataCache();
        for (LineageParseMode mode : LineageParseMode.values()) {
            LineageAnalyzeResult result = analyzer.analyzeScript(request(provider, cache, "V1")
                .options(LineageAnalyzeOptions.builder().parseMode(mode).build()).build());
            assertEquals(0, result.getFailedStatementCount());
            StatementLineageResult statement = result.getStatements().get(0);
            assertEquals(mode, statement.getParseMode());
            assertEquals(mode == LineageParseMode.COLUMN_ONLY ? 0 : 1, statement.getTableLineages().size());
            assertEquals(mode == LineageParseMode.TABLE_ONLY ? 0 : 2, statement.getColumnLineages().size());
        }
    }

    @Test
    public void sharedLegacyCacheReusesMetadataAcrossAnalyzersAndTaskVersions() {
        MutableMetadataProvider provider = new MutableMetadataProvider();
        HiveMetadataCache cache = new HiveMetadataCache();
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter();
        analyzer.analyzeScript(request(provider, cache, "V1").build());
        provider.sourceColumns = Arrays.asList("new_id", "new_name");

        LineageAnalyzeResult second = analyzer.analyzeScript(request(provider, cache, "V2").build());
        LineageAnalyzeResult third = new HiveScriptLineageAdapter().analyzeScript(request(provider, cache, "V3").build());

        assertEquals("V2", second.getTaskVersion());
        assertEquals("V3", third.getTaskVersion());
        assertColumns(second.getStatements().get(0), "user_id", "user_name");
        assertColumns(third.getStatements().get(0), "user_id", "user_name");
        assertEquals(2, provider.loads);
    }

    @Test
    public void freshLegacyCacheRefreshesMetadataWhenTheAnalyzerIsReused() {
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter();
        MutableMetadataProvider provider = new MutableMetadataProvider();
        analyzer.analyzeScript(request(provider, new HiveMetadataCache(), "V1").build());
        provider.sourceColumns = Arrays.asList("new_id", "new_name");
        HiveMetadataCache fresh = new HiveMetadataCache();

        LineageAnalyzeResult result = analyzer.analyzeScript(request(provider, fresh, "V2").build());

        assertColumns(result.getStatements().get(0), "new_id", "new_name");
        assertEquals(4, provider.loads);
        assertEquals(2, fresh.size());
    }

    @Test
    public void expiredLegacyCacheRefreshesMetadataBetweenRequests() throws InterruptedException {
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter();
        MutableMetadataProvider provider = new MutableMetadataProvider();
        HiveMetadataCache cache = new HiveMetadataCache(1L);
        analyzer.analyzeScript(request(provider, cache, "V1").build());
        provider.sourceColumns = Arrays.asList("new_id", "new_name");
        Thread.sleep(20L);

        LineageAnalyzeResult result = analyzer.analyzeScript(request(provider, cache, "V2").build());

        assertColumns(result.getStatements().get(0), "new_id", "new_name");
        assertEquals(4, provider.loads);
    }

    private static LineageAnalyzeRequest.Builder request(HiveMetadataProvider provider, HiveMetadataCache cache, String version) {
        return LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_001")
            .taskVersion(version)
            .defaultDatabase("default")
            .metadataProvider(provider)
            .metadataCache(cache)
            .rawScript(SQL)
            .options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build());
    }

    private static void assertColumns(StatementLineageResult statement, String firstSource, String secondSource) {
        assertEquals(Integer.valueOf(StatementParseStatus.SUCCESS.getCode()), statement.getParseStatusCode());
        assertEquals(2, statement.getColumnLineages().size());
        assertEquals("dwd.user_profile.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        assertEquals("dwd.user_profile.name", statement.getColumnLineages().get(1).getTargetColumn().getQualifiedName());
        assertEquals("ods.user_base." + firstSource,
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName());
        assertEquals("ods.user_base." + secondSource,
            statement.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName());
    }

    private static final class MutableMetadataProvider implements HiveMetadataProvider {
        private List<String> sourceColumns = Arrays.asList("user_id", "user_name");
        private int loads;

        @Override
        public HiveTableMetadata getTableMetadata(TableIdentifier table) {
            loads++;
            if ("ods.user_base".equals(table.getQualifiedName())) {
                return HiveTableMetadata.table(table, sourceColumns);
            }
            if ("dwd.user_profile".equals(table.getQualifiedName())) {
                return HiveTableMetadata.table(table, Arrays.asList("id", "name"));
            }
            throw new AssertionError("Unexpected metadata lookup: " + table.getQualifiedName());
        }
    }
}
