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

public class HiveQualifiedStarScopeTest {
    @Test
    public void nestedAliasDoesNotResolveOuterQualifiedStar() {
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("db1.a", "id")
            .addTable("db2.b", "id")
            .addTable("target.c", "id");
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_nested_alias_scope")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert into target.c select z.* from db1.a o "
                    + "join (select id from db2.b z) q on o.id = q.id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = new HiveScriptLineageAnalyzer()
            .analyzeScript(request).getStatements().get(0);

        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "outer unresolved star lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), "outer unresolved star sources");
    }
}
