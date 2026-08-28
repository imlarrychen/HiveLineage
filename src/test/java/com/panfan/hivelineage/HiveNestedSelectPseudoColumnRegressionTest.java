package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.result.WarningCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class HiveNestedSelectPseudoColumnRegressionTest {
    @Test
    public void nestedSelectDoesNotBecomeSourceColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.b", "id", "name", "value")
            .addTable("whc.c", "field1", "id", "name", "value")
            .addTable("whc.d", "field1", "id", "name", "value");

        String sql = "insert into whc.c(field1) select t.field1 from "
            + "(select a.field1 from whc.a left join whc.b on a.id = b.id) as t "
            + "left join whc.d on t.field1 = d.field1";
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_nested_select_regression")
            .taskId("task_nested_select_regression")
            .taskVersion("V1")
            .defaultDatabase("whc")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = new HiveScriptLineageAnalyzer()
            .analyzeScript(request)
            .getStatements()
            .get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "nested select parse status");
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "nested select field lineage count");
        assertDirectFieldLineage(statement.getColumnLineages().get(0), "whc.c.field1", "whc.a.field1");

        TestSupport.assertEquals(1, statement.getTableLineages().size(), "nested select table lineage count");
        Set<String> expectedSourceTables = new HashSet<>(Arrays.asList("whc.a", "whc.b", "whc.d"));
        TestSupport.assertEquals(expectedSourceTables, sourceTableNames(statement.getTableLineages().get(0)), "nested select source tables");

        Set<String> expectedJoinPairs = new HashSet<>(Arrays.asList(
            "whc.a.field1 = whc.d.field1",
            "whc.a.id = whc.b.id"
        ));
        TestSupport.assertEquals(expectedJoinPairs, resolvedJoinPairs(statement), "nested select resolved join pairs");
        assertNoUnknownColumnWarning(statement);
    }

    private void assertDirectFieldLineage(ColumnLineage lineage, String targetColumn, String sourceColumn) {
        TestSupport.assertEquals(targetColumn, lineage.getTargetColumn().getQualifiedName(), "direct target column");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), "direct source column count");
        TestSupport.assertEquals(sourceColumn, lineage.getSourceColumns().get(0).getQualifiedName(), "direct source column");
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), "direct lineage type");
    }

    private Set<String> sourceTableNames(TableLineage tableLineage) {
        Set<String> sourceNames = new HashSet<>();
        for (com.panfan.hivelineage.result.DataObjectRef sourceObject : tableLineage.getSourceObjects()) {
            sourceNames.add(sourceObject.getQualifiedName());
        }
        return sourceNames;
    }

    private Set<String> resolvedJoinPairs(StatementLineageResult statement) {
        Set<String> pairs = new HashSet<>();
        for (JoinCondition condition : statement.getJoinConditions()) {
            if (condition.getJoinColumns().size() != 2) {
                continue;
            }
            List<String> columns = new ArrayList<>();
            columns.add(condition.getJoinColumns().get(0).getQualifiedName());
            columns.add(condition.getJoinColumns().get(1).getQualifiedName());
            java.util.Collections.sort(columns);
            pairs.add(columns.get(0) + " = " + columns.get(1));
        }
        return pairs;
    }

    private void assertNoUnknownColumnWarning(StatementLineageResult statement) {
        for (LineageWarning warning : statement.getWarnings()) {
            TestSupport.assertTrue(
                warning.getWarningCode() != WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "nested select should not emit unknown column lineage warning"
            );
        }
    }
}
