package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.MapMetadataProvider;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;

public class HiveScopedViewOwnerResolutionTest {
    @Test
    public void ambiguousQualifiedOwnerInsideExpandedViewDoesNotChooseEitherTable() {
        MapMetadataProvider metadata = new MapMetadataProvider()
            .addTable("db1.b", "id")
            .addTable("db2.b", "id")
            .addTable("target.c", "id")
            .addView(
                "dim.v_ambiguous",
                "select b.id as id from db1.b join db2.b on db1.b.id = db2.b.id",
                "id"
            );
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_scoped_view_owner")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert into target.c select id from dim.v_ambiguous;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        StatementLineageResult statement = new HiveScriptLineageAnalyzer()
            .analyzeScript(request).getStatements().get(0);

        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "ambiguous view lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), "ambiguous view sources");
    }
}
