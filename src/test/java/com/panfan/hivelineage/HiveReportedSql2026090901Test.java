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

public class HiveReportedSql2026090901Test {
    @Test
    public void temporaryCreateWithQualifiedStarAlignsToFinalInsertTarget() {
        LineageAnalyzeResult result = analyze(caseOneSql(), metadata()
            .addTable("c_hjx002.v312_001971_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001971_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001971_04", "field1", "field2", "field3"));

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "case 1 failed statement count");
        StatementLineageResult statement = onlyReturnedStatement(result);
        TestSupport.assertEquals("c_hjx002.v312_001971_02", statement.getOutputObject().getQualifiedName(), "case 1 target");
        assertContainsSource(statement, "c_hjx002.v312_001971_04");
        assertColumnContains(statement, "c_hjx002.v312_001971_02.field1", "c_hjx002.v312_001971_04.field1");
    }

    @Test
    public void cteMustNotLeakAcrossStatementsAsTemporaryTable() {
        LineageAnalyzeResult result = analyze("c_hjx002", caseTwoSql(), metadata()
            .addTable("c_hjx002.v312_001970_01", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001970_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001970_03", "field1", "field2", "field3"));

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "case 2 failed statement count");
        StatementLineageResult statement = onlyReturnedStatement(result);
        assertContainsSource(statement, "c_hjx002.v312_001970_02");
        assertContainsSource(statement, "c_hjx002.v312_001970_03");
        assertDoesNotContainSource(statement, "c_hjx002.v312_tmp");
        assertColumnContains(statement, "c_hjx002.v312_001970_01.field1", "c_hjx002.v312_001970_02.field1");
        assertColumnContains(statement, "c_hjx002.v312_001970_01.field1", "c_hjx002.v312_001970_03.field1");
    }

    @Test
    public void qualifiedTemporaryCreateKeepsProjectedColumnLineageAcrossUseDatabase() {
        LineageAnalyzeResult result = analyze(caseThreeSql(), metadata()
            .addTable("c_hjx002.v312_001965_02", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001965_03", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001965_04", "field1", "field2", "field3")
            .addTable("c_hjx002.v312_001965_05", "field1", "field2", "field3"), true);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "case 3 failed statement count");
        TestSupport.assertEquals(2, result.getStatements().size(), "case 3 returned statement count");
        StatementLineageResult temporary = result.getStatements().get(0);
        StatementLineageResult statement = result.getStatements().get(1);

        assertColumnContains(
            temporary,
            "seele_tmp_db_wf_reported_2026090901_task_reported_2026090901.v312_001965_01.field1",
            "c_hjx002.v312_001965_04.field1"
        );
        assertColumnContains(statement, "c_hjx002.v312_001965_02.field1",
            "seele_tmp_db_wf_reported_2026090901_task_reported_2026090901.v312_001965_01.field1");
    }

    private LineageAnalyzeResult analyze(String sql, TestMetadataProvider metadata) {
        return analyze("default", sql, metadata, false);
    }

    private LineageAnalyzeResult analyze(String sql, TestMetadataProvider metadata, boolean includeTemporaryLineage) {
        return analyze("default", sql, metadata, includeTemporaryLineage);
    }

    private LineageAnalyzeResult analyze(String defaultDatabase, String sql, TestMetadataProvider metadata) {
        return analyze(defaultDatabase, sql, metadata, false);
    }

    private LineageAnalyzeResult analyze(
        String defaultDatabase,
        String sql,
        TestMetadataProvider metadata,
        boolean includeTemporaryLineage
    ) {
        return new HiveScriptLineageAnalyzer().analyzeScript(LineageAnalyzeRequest.builder()
            .workflowId("wf_reported_2026090901")
            .taskId("task_reported_2026090901")
            .taskVersion("V1")
            .defaultDatabase(defaultDatabase)
            .rawScript(sql)
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .includeTemporaryLineage(includeTemporaryLineage)
                .build())
            .build());
    }

    private TestMetadataProvider metadata() {
        return new TestMetadataProvider();
    }

    private StatementLineageResult onlyReturnedStatement(LineageAnalyzeResult result) {
        TestSupport.assertEquals(1, result.getStatements().size(), "returned statement count");
        return result.getStatements().get(0);
    }

    private void assertColumnContains(StatementLineageResult statement, String targetColumn, String sourceColumn) {
        ColumnLineage lineage = findColumnLineage(statement.getColumnLineages(), targetColumn);
        assertContainsColumn(lineage.getSourceColumns(), sourceColumn);
    }

    private void assertDoesNotContainSource(StatementLineageResult statement, String qualifiedName) {
        for (int i = 0; i < statement.getInputObjects().size(); i++) {
            if (qualifiedName.equals(statement.getInputObjects().get(i).getQualifiedName())) {
                throw new AssertionError("Did not expect source object " + qualifiedName);
            }
        }
    }

    private ColumnLineage findColumnLineage(List<ColumnLineage> lineages, String targetColumn) {
        for (ColumnLineage lineage : lineages) {
            if (targetColumn.equals(lineage.getTargetColumn().getQualifiedName())) {
                return lineage;
            }
        }
        throw new AssertionError("Expected target column " + targetColumn + ", all=" + lineages);
    }

    private String caseOneSql() {
        return "CREATE TEMPORARY TABLE IF NOT EXISTS c_hjx002.v312_001971_01\n"
            + "AS\n"
            + "SELECT DISTINCT a.* FROM c_hjx002.v312_001971_04 a\n"
            + "left join c_hjx002.v312_001971_03 b on a.field1 = b.field1\n"
            + "where a.field1 = '1';\n"
            + "\n"
            + "INSERT INTO c_hjx002.v312_001971_02\n"
            + "select * from c_hjx002.v312_001971_01;";
    }

    private String caseTwoSql() {
        return "WITH v312_tmp AS (\n"
            + "    SELECT * FROM c_hjx002.v312_001970_02\n"
            + "    UNION ALL\n"
            + "    SELECT * FROM c_hjx002.v312_001970_03\n"
            + ")\n"
            + "SELECT *\n"
            + "FROM v312_tmp;\n"
            + "\n"
            + "INSERT INTO c_hjx002.v312_001970_01\n"
            + "select * from v312_tmp;";
    }

    private String caseThreeSql() {
        return "use c_hjx002;\n"
            + "\n"
            + "CREATE TEMPORARY TABLE IF NOT EXISTS c_hjx001.v312_001965_01\n"
            + "AS\n"
            + "SELECT a.field1,b.field2,a.field3 FROM c_hjx002.v312_001965_04 a\n"
            + "left join c_hjx002.v312_001965_03 b on a.field1 = b.field1\n"
            + "where a.field1 = '1';\n"
            + "\n"
            + "INSERT INTO v312_001965_02\n"
            + "select concat(a.field1,b.field1) as field1,b.field2,a.field3 from c_hjx001.v312_001965_01 a\n"
            + "left join v312_001965_05 b \n"
            + "on a.field1=b.field1";
    }
}
