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

public class HiveReportedSql202609041400Test {
    @Test
    public void scalarSubqueryAfterUseDatabaseResolvesUnqualifiedInnerTable() {
        StatementLineageResult statement = analyze().getStatements().get(0);

        assertSuccessful(statement);
        assertColumnContains(statement, "c_hjx002.v312_001943_08.field1", "c_hjx002.v312_001943_06.field1");
        assertColumnContains(statement, "c_hjx002.v312_001943_08.field1", "c_hjx002.v312_001943_07.field1");
        assertColumnContains(statement, "c_hjx002.v312_001943_08.field2", "c_hjx002.v312_001943_06.field2");
        assertColumnContains(statement, "c_hjx002.v312_001943_08.field3", "c_hjx002.v312_001943_06.field3");
    }

    @Test
    public void mixedSetOperationResolvesColumnSourcesByPosition() {
        StatementLineageResult statement = analyze().getStatements().get(1);

        assertSuccessful(statement);
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field1", "c_hjx002.v312_001943_12.field1");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field1", "c_hjx002.v312_001943_01.field1");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field1", "c_hjx002.v312_001943_11.field1");
        assertDoesNotContainColumn(statement, "c_hjx002.v312_001943_13.field1", "c_hjx002.v312_001943_10.field1");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field2", "c_hjx002.v312_001943_12.field2");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field2", "c_hjx002.v312_001943_11.field2");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field2", "c_hjx002.v312_001943_10.field2");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field3", "c_hjx002.v312_001943_12.field3");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field3", "c_hjx002.v312_001943_11.field3");
        assertColumnContains(statement, "c_hjx002.v312_001943_13.field3", "c_hjx002.v312_001943_10.field3");
    }

    private LineageAnalyzeResult analyze() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001943_01", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_04", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_05", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_06", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_07", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_08", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_09", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_10", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_11", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_12", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001943_13", "field1", "field2", "field3");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_reported_202609041400")
            .taskId("task_reported_202609041400")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(reportedSql())
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(request);
        TestSupport.assertEquals(2, result.getStatements().size(), "statement count");
        return result;
    }

    private String reportedSql() {
        return "use c_hjx002;\n"
            + "INSERT INTO c_hjx002.v312_001943_08\n"
            + "select concat(field1,(SELECT field1 FROM v312_001943_07 limit 1)) as f1,field2,field3  \n"
            + "from v312_001943_06;\n"
            + "\n"
            + "INSERT INTO c_hjx002.v312_001943_13\n"
            + "select concat(a.field1,b.field1) as f1,a.field2,a.field3  \n"
            + "from c_hjx002.v312_001943_12 a\n"
            + "left join c_hjx002.v312_001943_01 b\n"
            + "on a.field1=b.field1\n"
            + "UNION\n"
            + "select * from c_hjx002.v312_001943_11\n"
            + "UNION ALL \n"
            + "select '1' as field1,field2,field3 from c_hjx002.v312_001943_10";
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

    private void assertDoesNotContainColumn(StatementLineageResult statement, String targetColumn, String unexpectedSource) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        for (int i = 0; i < lineage.getSourceColumns().size(); i++) {
            if (unexpectedSource.equals(lineage.getSourceColumns().get(i).getQualifiedName())) {
                throw new AssertionError("Did not expect source column " + unexpectedSource + ", all=" + allLineages(statement));
            }
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
