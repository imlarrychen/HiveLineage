package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.util.List;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;

public class HiveReportedSql202609032034Test {
    @Test
    public void ctasWithCteUnionAllStarResolvesBothBranchColumns() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001945_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001945_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001945_04", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001945_05", "field1", "field2", "field3");

        LineageAnalyzeResult result = analyze(
            metadata,
            "create table if not exists c_hjx002.v312_001945_01 as "
                + "with v312_tmp as ("
                + "select * from c_hjx002.v312_001945_03 "
                + "union all "
                + "select * from c_hjx002.v312_001945_04"
                + ") "
                + "select * from v312_tmp; "
                + "insert into c_hjx002.v312_001945_01 "
                + "select * from c_hjx002.v312_001945_02; "
                + "insert into c_hjx002.v312_001945_05 "
                + "select * from c_hjx002.v312_001945_01;"
        );

        StatementLineageResult ctas = result.getStatements().get(0);
        assertSuccessful(ctas);
        assertContainsSource(ctas, "c_hjx002.v312_001945_03");
        assertContainsSource(ctas, "c_hjx002.v312_001945_04");
        assertColumnContains(ctas, "c_hjx002.v312_001945_01.field1", "c_hjx002.v312_001945_03.field1");
        assertColumnContains(ctas, "c_hjx002.v312_001945_01.field1", "c_hjx002.v312_001945_04.field1");
        assertColumnContains(ctas, "c_hjx002.v312_001945_01.field2", "c_hjx002.v312_001945_03.field2");
        assertColumnContains(ctas, "c_hjx002.v312_001945_01.field2", "c_hjx002.v312_001945_04.field2");
        assertColumnContains(ctas, "c_hjx002.v312_001945_01.field3", "c_hjx002.v312_001945_03.field3");
        assertColumnContains(ctas, "c_hjx002.v312_001945_01.field3", "c_hjx002.v312_001945_04.field3");
    }

    @Test
    public void temporaryTableIfNotExistsIsRegisteredForLaterInsert() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001971_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001971_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001971_04", "field1", "field2", "field3");

        LineageAnalyzeResult result = analyze(
            metadata,
            "create temporary table if not exists c_hjx002.v312_001971_01 as "
                + "select a.field1, b.field2, a.field3 from c_hjx002.v312_001971_04 a "
                + "left join c_hjx002.v312_001971_03 b on a.field1 = b.field1 "
                + "where a.field1 = '1'; "
                + "insert into c_hjx002.v312_001971_02 "
                + "select * from c_hjx002.v312_001971_01;"
        );

        TestSupport.assertEquals(1, result.getStatements().size(), "returned statement count");
        StatementLineageResult insert = result.getStatements().get(0);
        assertSuccessful(insert);
        assertContainsSource(insert, "c_hjx002.v312_001971_04");
        assertContainsSource(insert, "c_hjx002.v312_001971_03");
        TestSupport.assertTrue(insert.getTableLineages().get(0).isThroughTemporaryTable(), "temporary chain marker");
        assertColumnContains(insert, "c_hjx002.v312_001971_02.field1", "c_hjx002.v312_001971_04.field1");
        assertColumnContains(insert, "c_hjx002.v312_001971_02.field2", "c_hjx002.v312_001971_03.field2");
        assertColumnContains(insert, "c_hjx002.v312_001971_02.field3", "c_hjx002.v312_001971_04.field3");
    }

    private LineageAnalyzeResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_reported_202609032034")
            .taskId("task_reported_202609032034")
            .taskVersion("V1")
            .defaultDatabase("c_hjx002")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request);
    }

    private void assertSuccessful(StatementLineageResult statement) {
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status, failure="
            + statement.getFailureCode() + ":" + statement.getFailureMessage());
    }

    private void assertColumnContains(StatementLineageResult statement, String targetColumn, String sourceColumn) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        assertContainsColumn(lineage.getSourceColumns(), sourceColumn);
    }

    private ColumnLineage findColumnLineage(List<ColumnLineage> lineages, String targetColumn) {
        for (ColumnLineage lineage : lineages) {
            if (targetColumn.equals(lineage.getTargetColumn().getQualifiedName())) {
                return lineage;
            }
        }
        throw new AssertionError("Expected target column " + targetColumn + ", actual=" + targetColumns(lineages));
    }

    private String targetColumns(List<ColumnLineage> lineages) {
        StringBuilder builder = new StringBuilder();
        for (ColumnLineage lineage : lineages) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(lineage.getTargetColumn().getQualifiedName());
        }
        return builder.toString();
    }
}
