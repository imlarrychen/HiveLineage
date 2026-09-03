package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.util.List;
import org.junit.Test;

public class HiveScalarSubqueryLineageTest {
    @Test
    public void insertSelectCountScalarSubqueryKeepsTableDependencyWithoutInventingColumnSource() {
        StatementLineageResult statement = analyze(
            "insert into whc.c(field1,id) "
                + "select a.field1,(select count(1) from whc.b) as id "
                + "from whc.a"
        );

        assertTableLineage(statement, "whc.c", "whc.a", "whc.b");
        assertColumnLineage(statement, "whc.c.field1", LineageType.DIRECT, "whc.a.field1");
        assertColumnLineage(statement, "whc.c.id", LineageType.DERIVED);
    }

    @Test
    public void insertSelectColumnScalarSubqueryMapsSubqueryColumnToTargetColumn() {
        StatementLineageResult statement = analyze(
            "insert into whc.c(field1,id) "
                + "select a.field1,(select id from whc.b limit 1) as id "
                + "from whc.a"
        );

        assertTableLineage(statement, "whc.c", "whc.a", "whc.b");
        assertColumnLineage(statement, "whc.c.field1", LineageType.DIRECT, "whc.a.field1");
        assertColumnLineage(statement, "whc.c.id", LineageType.DERIVED, "whc.b.id");
    }

    @Test
    public void insertSelectScalarSubqueryFromOuterQueryMapsInnerColumnToTargetColumn() {
        StatementLineageResult statement = analyze(
            "insert into whc.c(field1) "
                + "select (select a.field1 from whc.a limit 1) as field1 "
                + "from whc.b"
        );

        assertTableLineage(statement, "whc.c", "whc.a", "whc.b");
        assertColumnLineage(statement, "whc.c.field1", LineageType.DERIVED, "whc.a.field1");
    }

    @Test
    public void scalarSubqueryDerivedTableAliasWithoutAsDoesNotRequirePhysicalDatabase() {
        StatementLineageResult statement = analyzeV312(
            "insert into c_hjx002.v312_001925_02 "
                + "select field1, "
                + "(select a.field2 from "
                + "(select count(field1) as field2 from c_hjx002.v312_001925_01) a) as field2, "
                + "(select count(*) as field3 from c_hjx002.v312_001925_04) as field3 "
                + "from c_hjx002.v312_001925_03"
        );

        assertTableLineage(statement, "c_hjx002.v312_001925_02",
            "c_hjx002.v312_001925_01", "c_hjx002.v312_001925_03", "c_hjx002.v312_001925_04");
        assertColumnLineage(statement, "c_hjx002.v312_001925_02.field2",
            LineageType.DERIVED, "c_hjx002.v312_001925_01.field1");
        assertDoesNotContainColumn(statement, "c_hjx002.v312_001925_02.field2", "c_hjx002.v312_001925_03.field2");
    }

    @Test
    public void scalarSubqueryDerivedTableAliasWithAsDoesNotUseOuterDefaultSourceColumn() {
        StatementLineageResult statement = analyzeV312(
            "insert into c_hjx002.v312_001925_02 "
                + "select field1, "
                + "(select a.field2 from "
                + "(select count(field1) as field2 from c_hjx002.v312_001925_01) as a) as field2, "
                + "(select count(*) as field3 from c_hjx002.v312_001925_04) as field3 "
                + "from c_hjx002.v312_001925_03"
        );

        assertTableLineage(statement, "c_hjx002.v312_001925_02",
            "c_hjx002.v312_001925_01", "c_hjx002.v312_001925_03", "c_hjx002.v312_001925_04");
        assertColumnLineage(statement, "c_hjx002.v312_001925_02.field2",
            LineageType.DERIVED, "c_hjx002.v312_001925_01.field1");
        assertDoesNotContainColumn(statement, "c_hjx002.v312_001925_02.field2", "c_hjx002.v312_001925_03.field2");
    }

    private StatementLineageResult analyze(String sql) {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.b", "id", "name", "value")
            .addTable("whc.c", "field1", "id", "name", "value");

        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId("wf_scalar_subquery")
                .taskId("task_scalar_subquery")
                .taskVersion("V1")
                .defaultDatabase("whc")
                .metadataProvider(metadata)
                .metadataCache(new HiveMetadataCache())
                .rawScript(sql)
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .build())
                .build()
        );

        TestSupport.assertEquals(1, result.getStatements().size(), "statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status");
        return statement;
    }

    private StatementLineageResult analyzeV312(String sql) {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("c_hjx002.v312_001925_01", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001925_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001925_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001925_04", "field1", "field2", "field3");

        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId("wf_scalar_subquery")
                .taskId("task_scalar_subquery")
                .taskVersion("V1")
                .defaultDatabase("c_hjx002")
                .metadataProvider(metadata)
                .metadataCache(new HiveMetadataCache())
                .rawScript(sql)
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .build())
                .build()
        );

        TestSupport.assertEquals(1, result.getStatements().size(), "statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status, failure="
            + statement.getFailureCode() + ":" + statement.getFailureMessage());
        return statement;
    }

    private void assertTableLineage(StatementLineageResult statement, String targetTable, String... sourceTables) {
        TestSupport.assertEquals(1, statement.getTableLineages().size(), "table lineage count");
        TestSupport.assertEquals(targetTable, statement.getTableLineages().get(0).getTargetObject().getQualifiedName(), "target table");
        for (String sourceTable : sourceTables) {
            assertContainsSource(statement.getTableLineages().get(0).getSourceObjects(), sourceTable);
        }
        TestSupport.assertEquals(sourceTables.length, statement.getTableLineages().get(0).getSourceObjects().size(), "source table count");
    }

    private void assertColumnLineage(StatementLineageResult statement, String targetColumn, LineageType type, String... sources) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        TestSupport.assertEquals(type, lineage.getLineageType(), targetColumn + " lineage type");
        TestSupport.assertEquals(sources.length, lineage.getSourceColumns().size(),
            targetColumn + " source count, actual=" + sourceNames(lineage.getSourceColumns())
                + ", expression=" + lineage.getExpression());
        for (String source : sources) {
            assertContainsColumn(lineage.getSourceColumns(), source);
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

    private void assertContainsSource(List<DataObjectRef> objects, String qualifiedName) {
        for (DataObjectRef object : objects) {
            if (qualifiedName.equals(object.getQualifiedName())) {
                return;
            }
        }
        throw new AssertionError("Expected source table " + qualifiedName);
    }

    private void assertContainsColumn(List<ColumnRef> columns, String qualifiedName) {
        for (ColumnRef column : columns) {
            if (qualifiedName.equals(column.getQualifiedName())) {
                return;
            }
        }
        throw new AssertionError("Expected source column " + qualifiedName);
    }

    private void assertDoesNotContainColumn(StatementLineageResult statement, String targetColumn, String unexpectedSource) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        for (ColumnRef column : lineage.getSourceColumns()) {
            if (unexpectedSource.equals(column.getQualifiedName())) {
                throw new AssertionError("Did not expect source column " + unexpectedSource + " for " + targetColumn);
            }
        }
    }

    private String sourceNames(List<ColumnRef> columns) {
        StringBuilder builder = new StringBuilder();
        for (ColumnRef column : columns) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(column.getQualifiedName());
        }
        return builder.toString();
    }
}
