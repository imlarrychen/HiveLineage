package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.*;

public class HiveScopedCteProjectionWarningTest {
    @Test
    public void ambiguousQualifiedOwnerInsideCtePropagatesUnknownWarning() {
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.b", "id")
                .addTable("db2.b", "id")
                .addTable("target.c", "id"),
            "with q as (select b.id as id from db1.b join db2.b "
                + "on db1.b.id = db2.b.id) insert overwrite table target.c select q.id from q;"
        );

        assertUnknownEmptySource(statement, "ambiguous CTE owner");
    }

    @Test
    public void missingQualifiedOwnerInsideCtePropagatesUnknownWarning() {
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.b", "id")
                .addTable("target.c", "id"),
            "with q as (select missing.id as id from db1.b) "
                + "insert overwrite table target.c select q.id from q;"
        );

        assertUnknownEmptySource(statement, "missing CTE owner");
    }

    @Test
    public void constantCteProjectionDoesNotEmitUnknownWarning() {
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.b", "id")
                .addTable("target.c", "id"),
            "with q as (select 1 as id from db1.b) insert overwrite table target.c select q.id from q;"
        );

        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "constant CTE lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), "constant CTE sources");
        for (LineageWarning warning : statement.getWarnings()) {
            TestSupport.assertTrue(
                warning.getWarningCode() != WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "constant CTE should not emit unknown warning"
            );
        }
    }

    private StatementLineageResult analyze(MapMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_scoped_cte_warning")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private void assertUnknownEmptySource(StatementLineageResult statement, String message) {
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), message + " lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), message + " sources");
    }
}
