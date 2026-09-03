package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.util.List;
import org.junit.Test;

public class HiveReportedSql202609032019Test {
    @Test
    public void distinctStarWithPredicateSubqueriesExpandsPhysicalSourceColumns() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001969_01", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001969_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001969_03", "field1", "field2", "field3");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into c_hjx002.v312_001969_02 "
                + "select distinct * from c_hjx002.v312_001969_01 "
                + "where field1 in (select distinct field1 from c_hjx002.v312_001969_03) "
                + "and exists (select distinct field1 from c_hjx002.v312_001969_03 where field1 != '1') "
                + "and field1 not between 1 and 2 "
                + "or field1 in (select distinct field1 from c_hjx002.v312_001969_03) "
                + "group by field1, field2, field3 "
                + "order by field2 desc "
                + "limit 5 offset 2"
        );

        assertSuccessful(statement);
        assertColumnLineage(statement, "c_hjx002.v312_001969_02.field1", "c_hjx002.v312_001969_01.field1");
        assertColumnLineage(statement, "c_hjx002.v312_001969_02.field2", "c_hjx002.v312_001969_01.field2");
        assertColumnLineage(statement, "c_hjx002.v312_001969_02.field3", "c_hjx002.v312_001969_01.field3");
        assertNoSourceColumn(statement, "c_hjx002.v312_001969_01.DISTINCT");
    }

    @Test
    public void quotedReservedColumnsMapToTargetColumns() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001950_01", "type", "select", "field3", "date")
            .addTable("c_hjx002.v312_001950_02", "type", "select", "field3", "date");

        StatementLineageResult statement = analyze(
            metadata,
            "insert into c_hjx002.v312_001950_02 "
                + "select type as `type`, `select` as 'select', field3 as `insert`, `date` "
                + "from c_hjx002.v312_001950_01 "
                + "order by type"
        );

        assertSuccessful(statement);
        assertColumnLineage(statement, "c_hjx002.v312_001950_02.type", "c_hjx002.v312_001950_01.type");
        assertColumnLineage(statement, "c_hjx002.v312_001950_02.select", "c_hjx002.v312_001950_01.select");
        assertColumnLineage(statement, "c_hjx002.v312_001950_02.field3", "c_hjx002.v312_001950_01.field3");
        assertColumnLineage(statement, "c_hjx002.v312_001950_02.date", "c_hjx002.v312_001950_01.date");
    }

    @Test
    public void createViewWithExplicitColumnsAndStarSelectAlignsBySourceMetadata() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001998_01", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001998_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001998_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001998_04", "field1", "field2", "field3");

        StatementLineageResult statement = analyze(
            metadata,
            "create view if not exists c_hjx002.v312_001998_01 (field1, field2, field3) "
                + "as select * from c_hjx002.v312_001998_04"
        );

        assertSuccessful(statement);
        assertColumnLineage(statement, "c_hjx002.v312_001998_01.field1", "c_hjx002.v312_001998_04.field1");
        assertColumnLineage(statement, "c_hjx002.v312_001998_01.field2", "c_hjx002.v312_001998_04.field2");
        assertColumnLineage(statement, "c_hjx002.v312_001998_01.field3", "c_hjx002.v312_001998_04.field3");
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_reported_202609032019")
            .taskId("task_reported_202609032019")
            .taskVersion("V1")
            .defaultDatabase("c_hjx002")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private void assertSuccessful(StatementLineageResult statement) {
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status, failure="
            + statement.getFailureCode() + ":" + statement.getFailureMessage());
    }

    private void assertColumnLineage(StatementLineageResult statement, String targetColumn, String sourceColumn) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), targetColumn + " source count, actual="
            + sourceColumns(lineage) + ", all=" + allLineages(statement));
        TestSupport.assertEquals(sourceColumn, lineage.getSourceColumns().get(0).getQualifiedName(), targetColumn + " source");
    }

    private ColumnLineage findColumnLineage(List<ColumnLineage> lineages, String targetColumn) {
        for (ColumnLineage lineage : lineages) {
            if (targetColumn.equals(lineage.getTargetColumn().getQualifiedName())) {
                return lineage;
            }
        }
        throw new AssertionError("Expected target column " + targetColumn);
    }

    private void assertNoSourceColumn(StatementLineageResult statement, String unexpectedSource) {
        for (ColumnLineage lineage : statement.getColumnLineages()) {
            for (ColumnRef column : lineage.getSourceColumns()) {
                if (unexpectedSource.equals(column.getQualifiedName())) {
                    throw new AssertionError("Did not expect source column " + unexpectedSource);
                }
            }
        }
    }

    private String sourceColumns(ColumnLineage lineage) {
        StringBuilder builder = new StringBuilder();
        for (ColumnRef column : lineage.getSourceColumns()) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(column.getQualifiedName());
        }
        return builder.toString();
    }

    private String allLineages(StatementLineageResult statement) {
        StringBuilder builder = new StringBuilder();
        for (ColumnLineage lineage : statement.getColumnLineages()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(lineage.getTargetColumn().getQualifiedName()).append("<-").append(sourceColumns(lineage));
        }
        return builder.toString();
    }
}
