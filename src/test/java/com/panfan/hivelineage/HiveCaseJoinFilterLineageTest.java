package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;

public class HiveCaseJoinFilterLineageTest {
    @Test
    public void caseExpressionWithJoinAndFilterKeepsOnlyProjectedSourceColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_002000_01", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_002000_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_002000_03", "field1", "field2", "field3");

        StatementLineageResult statement = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId("wf_case_join_filter")
                .taskId("task_case_join_filter")
                .taskVersion("V1")
                .defaultDatabase("c_hjx002")
                .metadataProvider(metadata)
                .metadataCache(new HiveMetadataCache())
                .rawScript(
                    "INSERT INTO c_hjx002.v312_002000_02(field1) "
                        + "SELECT DISTINCT "
                        + "CASE "
                        + "WHEN a.field1 < 30 THEN '青年' "
                        + "WHEN a.field1 BETWEEN 30 AND 50 THEN '中年' "
                        + "ELSE '老年' "
                        + "END AS age_group "
                        + "FROM c_hjx002.v312_002000_01 a "
                        + "left join c_hjx002.v312_002000_03 b "
                        + "on a.field1 = b.field1 "
                        + "where b.field1 >= 1000 "
                        + "and b.field2 = 'a.field1'"
                )
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .parseJoinConditions(true)
                    .build())
                .build()
        ).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status");
        TestSupport.assertEquals("c_hjx002.v312_002000_02", statement.getOutputObject().getQualifiedName(), "output");
        assertContainsSource(statement, "c_hjx002.v312_002000_01");
        assertContainsSource(statement, "c_hjx002.v312_002000_03");

        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "column lineage count");
        ColumnLineage lineage = statement.getColumnLineages().get(0);
        TestSupport.assertEquals("c_hjx002.v312_002000_02.field1", lineage.getTargetColumn().getQualifiedName(), "target");
        TestSupport.assertEquals(LineageType.DERIVED, lineage.getLineageType(), "lineage type");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "projected source count "
            + sourceColumnNames(lineage));
        TestSupport.assertEquals(
            "c_hjx002.v312_002000_01.field1",
            lineage.getSourceColumns().get(0).getQualifiedName(),
            "projected source"
        );
        TestSupport.assertEquals(
            "case when a.field1 < 30 then '青年' when a.field1 between 30 and 50 then '中年' else '老年' end",
            lineage.getExpression(),
            "projected expression"
        );

        TestSupport.assertEquals(1, statement.getJoinConditions().size(), "join condition count");
        JoinCondition joinCondition = statement.getJoinConditions().get(0);
        TestSupport.assertEquals("LEFT_JOIN", joinCondition.getJoinTypeName(), "join type");
        TestSupport.assertEquals("a.field1 = b.field1", joinCondition.getJoinExpression(), "join expression");
        assertContainsColumn(joinCondition.getJoinColumns(), "c_hjx002.v312_002000_01.field1");
        assertContainsColumn(joinCondition.getJoinColumns(), "c_hjx002.v312_002000_03.field1");
    }

    private String sourceColumnNames(ColumnLineage lineage) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < lineage.getSourceColumns().size(); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(lineage.getSourceColumns().get(i).getQualifiedName());
        }
        return names.toString();
    }
}
