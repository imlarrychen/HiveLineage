package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static org.junit.Assert.assertEquals;

public class HiveSessionDatabaseResolutionTest {
    @Test
    public void defaultDatabaseQualifiesBareTablesAndColumns() {
        StatementLineageResult statement = analyze(
            "whc",
            "insert into target_t(id) select s.id from source_t s;",
            LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
            null
        ).getStatements().get(0);

        assertEquals("whc.target_t", statement.getOutputObject().getQualifiedName());
        assertContainsSource(statement, "whc.source_t");
        assertEquals("whc.target_t.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "whc.source_t.id");
    }

    @Test
    public void explicitDatabaseWinsOverSessionDatabase() {
        StatementLineageResult statement = analyze(
            "db1",
            "insert into db2.target_t(id) select id from db3.source_t;",
            LineageAnalyzeOptions.builder().build(),
            null
        ).getStatements().get(0);

        assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
        assertContainsSource(statement, "db3.source_t");
    }

    @Test
    public void fullyQualifiedTablesDoNotRequireDefaultDatabase() {
        StatementLineageResult statement = analyze(
            null,
            "insert into db2.target_t(id) select id from db3.source_t;",
            LineageAnalyzeOptions.builder().build(),
            null
        ).getStatements().get(0);

        assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
        assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName());
        assertContainsSource(statement, "db3.source_t");
    }

    @Test
    public void backtickAliasQualifiesSourceColumn() {
        StatementLineageResult statement = analyze(
            "d",
            "insert into out_t(id) select `S`.`ID` from source_t `S`;",
            LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build(),
            null
        ).getStatements().get(0);

        assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "d.source_t.id");
    }

    private LineageAnalyzeResult analyze(
        String defaultDatabase,
        String script,
        LineageAnalyzeOptions options,
        HiveMetadataProvider metadataProvider
    ) {
        return new HiveScriptLineageAnalyzer().analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_database")
            .taskId("task_database")
            .taskVersion("V1")
            .defaultDatabase(defaultDatabase)
            .rawScript(script)
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .options(options)
            .build());
    }
}
