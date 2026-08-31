package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.util.List;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;

public class HiveAdvancedColumnLineageTest {
    @Test
    public void subqueryAliasDerivedColumnKeepsProjectionExpression() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        AnalyzerTestSupport.MapMetadataProvider metadataProvider = new AnalyzerTestSupport.MapMetadataProvider()
            .addTable("ods.order_detail", "user_id", "order_id", "amount")
            .addTable("dwd.inline_subquery_target", "user_id", "order_cnt");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_subquery_projection")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.inline_subquery_target(user_id, order_cnt) "
                    + "select x.user_id, x.order_cnt "
                    + "from (select user_id, count(*) as order_cnt from ods.order_detail group by user_id) x;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        ColumnLineage orderCount = statement.getColumnLineages().get(1);

        TestSupport.assertEquals(
            "dwd.inline_subquery_target.order_cnt",
            orderCount.getTargetColumn().getQualifiedName(),
            "subquery derived target"
        );
        TestSupport.assertTrue(orderCount.getSourceColumns().isEmpty(), "count star should not invent source column");
        TestSupport.assertEquals("count(*)", orderCount.getExpression(), "subquery derived expression");
        TestSupport.assertEquals(LineageType.DERIVED, orderCount.getLineageType(), "subquery derived type");
    }

    @Test
    public void createTableAsWithSelectProducesColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        AnalyzerTestSupport.MapMetadataProvider metadataProvider = new AnalyzerTestSupport.MapMetadataProvider()
            .addTable("ods.order_detail", "user_id", "order_id", "amount", "pay_time", "pay_status", "dt")
            .addTable("dwd.user_order_summary", "user_id", "total_amount", "order_cnt", "last_pay_time");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_cte_ctas")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "create table if not exists dwd.user_order_summary stored as parquet as "
                    + "with paid_orders as ("
                    + "select user_id, order_id, amount, pay_time from ods.order_detail where pay_status = 'PAID'"
                    + ") "
                    + "select user_id, sum(amount) as total_amount, count(*) as order_cnt, "
                    + "max(pay_time) as last_pay_time from paid_orders group by user_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals("CREATE_TABLE_AS_SELECT", statement.getStatementType(), "ctas with cte statement type");
        assertContainsSource(statement, "ods.order_detail");
        List<ColumnLineage> columns = statement.getColumnLineages();
        TestSupport.assertEquals(
            "dwd.user_order_summary.user_id",
            columns.get(0).getTargetColumn().getQualifiedName(),
            "ctas cte user_id target"
        );
        TestSupport.assertEquals(
            "ods.order_detail.user_id",
            columns.get(0).getSourceColumns().get(0).getQualifiedName(),
            "ctas cte user_id source"
        );
        TestSupport.assertEquals(LineageType.DIRECT, columns.get(0).getLineageType(), "ctas cte user_id type");
        TestSupport.assertEquals(
            "ods.order_detail.amount",
            columns.get(1).getSourceColumns().get(0).getQualifiedName(),
            "ctas cte total amount source"
        );
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(1).getLineageType(), "ctas cte total amount type");
        TestSupport.assertEquals("sum(amount)", columns.get(1).getExpression(), "ctas cte total amount expression");
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(2).getLineageType(), "ctas cte count type");
        TestSupport.assertEquals("count(*)", columns.get(2).getExpression(), "ctas cte count expression");
        TestSupport.assertEquals(
            "ods.order_detail.pay_time",
            columns.get(3).getSourceColumns().get(0).getQualifiedName(),
            "ctas cte last pay time source"
        );
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(3).getLineageType(), "ctas cte last pay time type");
    }

    @Test
    public void lateralViewExplodeKeepsTableAndExplodeArgumentColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_029")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert overwrite table dwd.user_tags(user_id, tag) "
                    + "select user_id, tag "
                    + "from ods.user_base lateral view explode(tags) exploded_tags as tag;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "lateral view parse status");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals(
            "ods.user_base.user_id",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "ordinary lateral source"
        );
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.user_base.tags");
        TestSupport.assertEquals(
            LineageType.DERIVED,
            statement.getColumnLineages().get(1).getLineageType(),
            "lateral explode tag type"
        );
        TestSupport.assertEquals(
            "explode(tags)",
            statement.getColumnLineages().get(1).getExpression(),
            "lateral explode tag expression"
        );
    }
}
