package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsSource;

public class HiveUnresolvedProjectionJoinTest {
    @Test
    public void unresolvedViewExpressionDoesNotFallBackToViewColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db1.a", "id")
            .addTable("target.c", "id")
            .addView("dim.v", "select missing.id as id from db1.a", "id");
        StatementLineageResult statement = analyze(
            metadata,
            "insert overwrite table target.c select v.id + 1 from dim.v v"
        );

        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "unresolved view expression count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), "unresolved view sources");
    }

    @Test
    public void unresolvedViewJoinDoesNotBecomePhysicalViewSource() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db1.a", "id")
            .addView("dim.v", "select missing.id as id from db1.a", "id");
        StatementLineageResult statement = analyze(
            metadata,
            "select * from db1.a a join dim.v v on v.id = a.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertTrue(!containsColumn(join.getJoinColumns(), "dim.v.id"), "unresolved view join column");
        TestSupport.assertTrue(!containsSource(join.getJoinTables(), "dim.v"), "unresolved view join table");
        assertContainsColumn(join.getJoinColumns(), "db1.a.id");
    }

    @Test
    public void nestedUnresolvedCteJoinDoesNotFallBackToIntermediateColumn() {
        StatementLineageResult statement = analyze(
            new TestMetadataProvider().addTable("db1.a", "id").addTable("db2.b", "id"),
            "with q1 as (select missing.id as id from db1.a), "
                + "q2 as (select q1.id as id from q1) "
                + "select * from q2 join db2.b b on q2.id = b.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertTrue(!containsColumn(join.getJoinColumns(), "q1.id"), "unresolved nested CTE column");
        TestSupport.assertTrue(!containsColumn(join.getJoinColumns(), "q2.id"), "unresolved outer CTE column");
        assertContainsColumn(join.getJoinColumns(), "db2.b.id");
    }

    @Test
    public void resolvedSetCteJoinMergesAllBranchSources() {
        StatementLineageResult statement = analyze(
            new TestMetadataProvider()
                .addTable("db1.a", "id")
                .addTable("db2.b", "id")
                .addTable("db3.c", "id"),
            "with q as (select a.id as id from db1.a a union all "
                + "select b.id as id from db2.b b) "
                + "select * from q join db3.c c on q.id = c.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        assertContainsColumn(join.getJoinColumns(), "db1.a.id");
        assertContainsColumn(join.getJoinColumns(), "db2.b.id");
        assertContainsColumn(join.getJoinColumns(), "db3.c.id");
        assertNoUnknownWarning(statement, "resolved set CTE");
    }

    @Test
    public void resolvedViewJoinExpandsToUnderlyingPhysicalColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db1.source", "id")
            .addTable("db2.b", "id")
            .addView("dim.v", "select s.id as id from db1.source s", "id");
        StatementLineageResult statement = analyze(
            metadata,
            "select * from db2.b b join dim.v v on v.id = b.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        assertContainsColumn(join.getJoinColumns(), "db1.source.id");
        assertContainsColumn(join.getJoinColumns(), "db2.b.id");
        assertNoUnknownWarning(statement, "resolved view");
    }

    @Test
    public void resolvedNestedCteJoinExpandsThroughEveryCte() {
        StatementLineageResult statement = analyze(
            new TestMetadataProvider().addTable("db1.a", "id").addTable("db2.b", "id"),
            "with q1 as (select a.id as id from db1.a a), "
                + "q2 as (select q1.id as id from q1) "
                + "select * from q2 join db2.b b on q2.id = b.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        assertContainsColumn(join.getJoinColumns(), "db1.a.id");
        assertContainsColumn(join.getJoinColumns(), "db2.b.id");
        assertNoUnknownWarning(statement, "resolved nested CTE");
    }

    @Test
    public void constantViewJoinDoesNotEmitUnknownOrFabricateViewColumn() {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("db1.source", "id")
            .addTable("db2.b", "id")
            .addView("dim.v", "select 1 as id from db1.source", "id");
        StatementLineageResult statement = analyze(
            metadata,
            "select * from db2.b b join dim.v v on v.id = b.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        TestSupport.assertTrue(!containsColumn(join.getJoinColumns(), "dim.v.id"), "constant view join column");
        assertContainsColumn(join.getJoinColumns(), "db2.b.id");
        assertNoUnknownWarning(statement, "constant view");
    }

    @Test
    public void constantCteJoinDoesNotEmitUnknownOrFabricateCteColumn() {
        StatementLineageResult statement = analyze(
            new TestMetadataProvider().addTable("db1.a", "id").addTable("db2.b", "id"),
            "with q as (select 1 as id from db1.a) "
                + "select * from q join db2.b b on q.id = b.id"
        );
        JoinCondition join = statement.getJoinConditions().get(0);

        TestSupport.assertTrue(!containsColumn(join.getJoinColumns(), "q.id"), "constant CTE join column");
        assertContainsColumn(join.getJoinColumns(), "db2.b.id");
        assertNoUnknownWarning(statement, "constant CTE");
    }

    private StatementLineageResult analyze(TestMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_projection_join")
            .taskId("task_projection_join")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql + ";")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .parseJoinConditions(true)
                .expandViewLineage(true)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private void assertNoUnknownWarning(StatementLineageResult statement, String message) {
        for (LineageWarning warning : statement.getWarnings()) {
            TestSupport.assertTrue(
                warning.getWarningCode() != WarningCode.UNKNOWN_COLUMN_LINEAGE,
                message + " should not emit unknown warning"
            );
        }
    }
}
