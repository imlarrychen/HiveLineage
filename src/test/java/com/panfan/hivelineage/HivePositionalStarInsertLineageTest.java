package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import org.junit.Test;

public class HivePositionalStarInsertLineageTest {
    @Test
    public void singleSourceStarMapsDifferentColumnNamesByPosition() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.whc_hive_01", "id", "name", "age")
            .addTable("whc.whc_hive_02", "id", "name", "sex");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into whc.whc_hive_01 select * from whc.whc_hive_02"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "positional star parse status");
        TestSupport.assertEquals(3, statement.getColumnLineages().size(), "positional star lineage count");
        assertDirect(statement.getColumnLineages().get(0), "whc.whc_hive_01.id", "whc.whc_hive_02.id");
        assertDirect(statement.getColumnLineages().get(1), "whc.whc_hive_01.name", "whc.whc_hive_02.name");
        assertDirect(statement.getColumnLineages().get(2), "whc.whc_hive_01.age", "whc.whc_hive_02.sex");
    }

    @Test
    public void singleSourceStarFailsWhenTargetHasExtraColumns() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.target_more", "id", "name", "age", "extra_col")
            .addTable("whc.source_less", "id", "name", "sex");

        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table whc.target_more select * from whc.source_less"
        );

        TestSupport.assertEquals(2, statement.getParseStatusCode(), "target extra star parse status");
        TestSupport.assertEquals("COLUMN_COUNT_MISMATCH", statement.getFailureCode(), "target extra star failure code");
        TestSupport.assertEquals(0, statement.getColumnLineages().size(), "target extra star lineage count");
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_positional_star")
            .taskId("task_positional_star")
            .taskVersion("V1")
            .defaultDatabase("whc")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private void assertDirect(ColumnLineage lineage, String target, String source) {
        TestSupport.assertEquals(target, lineage.getTargetColumn().getQualifiedName(), "positional target");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "positional source count");
        TestSupport.assertEquals(source, lineage.getSourceColumns().get(0).getQualifiedName(), "positional source");
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "positional lineage type");
    }
}
