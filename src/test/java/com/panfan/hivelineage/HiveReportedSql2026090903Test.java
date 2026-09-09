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

public class HiveReportedSql2026090903Test {
    @Test
    public void chainedCteInsertWithSurroundingDdlResolvesAllPhysicalSources() throws Exception {
        TestMetadataProvider metadata = metadata();

        LineageAnalyzeResult result = analyze(metadata, reportedSqlWithDdl());

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "returned statement count");
        TestSupport.assertEquals(5, result.getIgnoredStatementCount(), "ignored DDL statement count");

        StatementLineageResult statement = result.getStatements().get(0);
        assertContainsSource(statement, "c_hjx002.v312_002005_01");
        assertContainsSource(statement, "c_hjx002.v312_002005_03");
        assertContainsSource(statement, "c_hjx002.v312_002005_04");
        assertContainsSource(statement, "c_hjx002.v312_002005_05");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.region", "c_hjx002.v312_002005_03.field1");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.product", "c_hjx002.v312_002005_04.field2");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.sales", "c_hjx002.v312_002005_05.field3");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.cumulative_sales", "c_hjx002.v312_002005_01.sales");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.cumulative_cost", "c_hjx002.v312_002005_01.cost");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.growth_rate", "c_hjx002.v312_002005_01.sales");
    }

    private TestMetadataProvider metadata() {
        return new TestMetadataProvider()
            .addTable("c_hjx002.v312_002005_01", "id", "date", "region", "product", "sales", "cost")
            .addTable("c_hjx002.v312_002005_02",
                "id",
                "date",
                "region",
                "product",
                "sales",
                "cost",
                "cumulative_sales",
                "cumulative_cost",
                "growth_rate",
                "profit",
                "profit_margin",
                "sales_rank",
                "moving_avg_sales",
                "total_sales_by_region",
                "total_cost_by_region",
                "total_sales_by_product",
                "total_cost_by_product")
            .addTable("c_hjx002.v312_002005_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_002005_04", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_002005_05", "field1", "field2", "field3");
    }

    private LineageAnalyzeResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_reported_2026090903")
            .taskId("task_reported_2026090903")
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

    private String reportedSqlWithDdl() throws Exception {
        return AnalyzerTestSupport.readWorkspaceFile("补充案例/sql解析问题2026090903.txt")
            .replace("Hive的建表语句如下：", "")
            .replace("解析的Hive语句如下：", "");
    }

    private void assertColumnContains(StatementLineageResult statement, String targetColumn, String sourceColumn) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        try {
            assertContainsColumn(lineage.getSourceColumns(), sourceColumn);
        } catch (AssertionError ex) {
            throw new AssertionError(ex.getMessage() + ", all=" + allLineages(statement));
        }
    }

    private ColumnLineage findColumnLineage(List<ColumnLineage> lineages, String targetColumn) {
        for (ColumnLineage lineage : lineages) {
            if (targetColumn.equals(lineage.getTargetColumn().getQualifiedName())) {
                return lineage;
            }
        }
        throw new AssertionError("Expected target column " + targetColumn + ", all=" + allLineages(lineages));
    }

    private String allLineages(StatementLineageResult statement) {
        return allLineages(statement.getColumnLineages());
    }

    private String allLineages(List<ColumnLineage> lineages) {
        StringBuilder builder = new StringBuilder();
        for (ColumnLineage lineage : lineages) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(lineage.getTargetColumn().getQualifiedName()).append("<-");
            for (int i = 0; i < lineage.getSourceColumns().size(); i++) {
                if (i > 0) {
                    builder.append(",");
                }
                builder.append(lineage.getSourceColumns().get(i).getQualifiedName());
            }
            builder.append(" expr=").append(lineage.getExpression());
        }
        return builder.toString();
    }
}
