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

public class HiveInsertIntoDistinctLineageTest {
    @Test
    public void distinctModifierDoesNotBecomeSourceColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.c", "field1", "id", "name", "value");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into whc.c(field1) select distinct field1 from whc.a"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "distinct parse status");
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "distinct lineage count");
        assertDirect(statement.getColumnLineages().get(0), "whc.c.field1", "whc.a.field1");
    }

    @Test
    public void insertIntoStaticPartitionKeepsConstantLineage() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.c", "field1", "dt");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into whc.c partition(dt='20260827') select distinct field1 from whc.a"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "static partition parse status");
        TestSupport.assertEquals(2, statement.getColumnLineages().size(), "static partition lineage count");
        assertDirect(statement.getColumnLineages().get(0), "whc.c.field1", "whc.a.field1");

        ColumnLineage partitionLineage = statement.getColumnLineages().get(1);
        TestSupport.assertEquals("whc.c.dt", partitionLineage.getTargetColumn().getQualifiedName(), "static partition target");
        TestSupport.assertEquals("constant('20260827')", partitionLineage.getExpression(), "static partition expression");
        TestSupport.assertEquals(LineageType.CONSTANT, partitionLineage.getLineageType(), "static partition lineage type");
        TestSupport.assertEquals(0, partitionLineage.getSourceColumns().size(), "static partition source count");
    }

    @Test
    public void insertIntoConstantSelectWithoutFromSucceeds() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("sum_cor_110.xc_dept", "dept_id", "dept_name", "dept_type", "parent_id", "root_id", "biz_date");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into sum_cor_110.xc_dept select '1100++','全部单位','D','1100++','1100++',udf_pek_date('20260921',0,1,0,0,-1)"
        );

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "constant insert parse status=" + statement.getFailureMessage());
        TestSupport.assertEquals("sum_cor_110.xc_dept", statement.getOutputObject().getQualifiedName(), "constant insert target");
        TestSupport.assertEquals(0, statement.getInputObjects().size(), "constant insert source tables");
        TestSupport.assertEquals(0, statement.getTableLineages().size(), "constant insert table lineage");
        TestSupport.assertEquals(6, statement.getColumnLineages().size(), "constant insert column count");
        for (int i = 0; i < statement.getColumnLineages().size(); i++) {
            ColumnLineage column = statement.getColumnLineages().get(i);
            TestSupport.assertEquals(i == 5 ? LineageType.DERIVED : LineageType.CONSTANT,
                column.getLineageType(), "constant insert type " + column.getTargetColumn().getQualifiedName());
            TestSupport.assertEquals(0, column.getSourceColumns().size(), "constant insert source columns");
        }
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_insert_into_distinct")
            .taskId("task_insert_into_distinct")
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
        TestSupport.assertEquals(target, lineage.getTargetColumn().getQualifiedName(), "direct target");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "direct source count");
        TestSupport.assertEquals(source, lineage.getSourceColumns().get(0).getQualifiedName(), "direct source");
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "direct lineage type");
    }
}
