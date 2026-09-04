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

public class HiveReportedSql20260904Test {
    @Test
    public void chainedCteJoinProjectionResolvesOverriddenColumnsToPhysicalSources() throws Exception {
        TestMetadataProvider metadata = new TestMetadataProvider()
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
            .addTable("c_hjx001.v312_002005_03", "field1", "field2", "field3")
            .addTable("c_hjx001.v312_002005_04", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_002005_05", "field1", "field2", "field3");

        StatementLineageResult statement = analyze(metadata);

        assertSuccessful(statement);
        assertContainsSource(statement, "c_hjx002.v312_002005_01");
        assertContainsSource(statement, "c_hjx001.v312_002005_03");
        assertContainsSource(statement, "c_hjx001.v312_002005_04");
        assertContainsSource(statement, "c_hjx002.v312_002005_05");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.id", "c_hjx002.v312_002005_01.id");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.date", "c_hjx002.v312_002005_01.date");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.region", "c_hjx001.v312_002005_03.field1");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.product", "c_hjx001.v312_002005_04.field2");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.sales", "c_hjx002.v312_002005_05.field3");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.cost", "c_hjx002.v312_002005_01.cost");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.cumulative_cost", "c_hjx002.v312_002005_01.date");
        assertColumnContains(statement, "c_hjx002.v312_002005_02.moving_avg_sales", "c_hjx002.v312_002005_01.date");
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata) {
        String sql = reportedSql();
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_reported_20260904")
            .taskId("task_reported_20260904")
            .taskVersion("V1")
            .defaultDatabase("c_hjx002")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(request);
        return result.getStatements().get(0);
    }

    private String reportedSql() {
        return "WITH \n"
            + "-- 第一步：计算累计销售额和累计成本\n"
            + "cte1 AS (\n"
            + "    SELECT \n"
            + "        a.id,\n"
            + "        a.`date`,\n"
            + "        b.field1 as region,\n"
            + "        a.product,\n"
            + "        a.sales,\n"
            + "        a.cost,\n"
            + "        SUM(a.sales) OVER (PARTITION BY a.region, a.product ORDER BY a.`date`) AS cumulative_sales,\n"
            + "        SUM(a.cost) OVER (PARTITION BY a.region, a.product ORDER BY `date`) AS cumulative_cost,\n"
            + "        (a.sales - LAG(a.sales, 1) OVER (PARTITION BY a.region, a.product ORDER BY `date`)) / \n"
            + "            LAG(a.sales, 1) OVER (PARTITION BY a.region, a.product ORDER BY a.`date`) AS growth_rate\n"
            + "    FROM \n"
            + "        c_hjx002.v312_002005_01 a\n"
            + "        left join c_hjx001.v312_002005_03 b on a.region != b.field1\n"
            + "),\n"
            + "cte2 AS (\n"
            + "    SELECT id, `date`, region, product, sales, cost, cumulative_sales, cumulative_cost, growth_rate,\n"
            + "        sales - cost AS profit,\n"
            + "        (sales - cost) / sales AS profit_margin\n"
            + "    FROM cte1\n"
            + "),\n"
            + "cte3 AS (\n"
            + "    SELECT id, `date`, region, product, sales, cost, cumulative_sales, cumulative_cost, growth_rate,\n"
            + "        profit, profit_margin,\n"
            + "        RANK() OVER (PARTITION BY region, product ORDER BY sales DESC) AS sales_rank,\n"
            + "        AVG(sales) OVER (\n"
            + "            PARTITION BY region \n"
            + "            ORDER BY `date` \n"
            + "            ROWS BETWEEN 2 PRECEDING AND CURRENT ROW\n"
            + "        ) AS moving_avg_sales\n"
            + "    FROM cte2\n"
            + "),\n"
            + "cte4 AS (\n"
            + "    SELECT \n"
            + "        a.id,\n"
            + "        a.`date`,\n"
            + "        a.region,\n"
            + "        b.field2 as product,\n"
            + "        a.sales,\n"
            + "        a.cost,\n"
            + "        a.cumulative_sales,\n"
            + "        a.cumulative_cost,\n"
            + "        a.growth_rate,\n"
            + "        a.profit,\n"
            + "        a.profit_margin,\n"
            + "        a.sales_rank,\n"
            + "        a.moving_avg_sales,\n"
            + "        SUM(a.sales) OVER (PARTITION BY a.region) AS total_sales_by_region,\n"
            + "        SUM(a.cost) OVER (PARTITION BY a.region) AS total_cost_by_region,\n"
            + "        SUM(a.sales) OVER (PARTITION BY a.product) AS total_sales_by_product,\n"
            + "        SUM(a.cost) OVER (PARTITION BY a.product) AS total_cost_by_product\n"
            + "    FROM \n"
            + "        cte3 a\n"
            + "        left join c_hjx001.v312_002005_04 b on a.region != b.field1\n"
            + ")\n"
            + "INSERT INTO c_hjx002.v312_002005_02\n"
            + "SELECT \n"
            + "    a.id,\n"
            + "    a.`date`,\n"
            + "    a.region,\n"
            + "    a.product,\n"
            + "    b.field3 as sales,\n"
            + "    a.cost,\n"
            + "    a.cumulative_sales,\n"
            + "    a.cumulative_cost,\n"
            + "    a.growth_rate,\n"
            + "    a.profit,\n"
            + "    a.profit_margin,\n"
            + "    a.sales_rank,\n"
            + "    a.moving_avg_sales,\n"
            + "    a.total_sales_by_region,\n"
            + "    a.total_cost_by_region,\n"
            + "    a.total_sales_by_product,\n"
            + "    a.total_cost_by_product\n"
            + "FROM \n"
            + "    cte4 a\n"
            + "    left join c_hjx002.v312_002005_05 b \n"
            + "    on a.region != b.field1";
    }

    private void assertSuccessful(StatementLineageResult statement) {
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status, failure="
            + statement.getFailureCode() + ":" + statement.getFailureMessage());
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
        throw new AssertionError("Expected target column " + targetColumn);
    }

    private String allLineages(StatementLineageResult statement) {
        StringBuilder builder = new StringBuilder();
        for (ColumnLineage lineage : statement.getColumnLineages()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(lineage.getTargetColumn().getQualifiedName())
                .append("<-");
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
