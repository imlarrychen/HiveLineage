package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.*;

public class HiveScopedCteProjectionWarningTest {
    private static final String[] SET_OPERATORS = {"union all", "intersect", "except"};

    @Test
    public void ambiguousQualifiedOwnerInsideCtePropagatesUnknownWarning() {
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.b", "id")
                .addTable("db2.b", "id")
                .addTable("target.c", "id"),
            "with q as (select b.id as id from db1.b join db2.b "
                + "on db1.b.id = db2.b.id) insert overwrite table target.c select q.id from q;"
        );

        assertUnknownEmptySource(statement, "ambiguous CTE owner");
    }

    @Test
    public void missingQualifiedOwnerInsideCtePropagatesUnknownWarning() {
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.b", "id")
                .addTable("target.c", "id"),
            "with q as (select missing.id as id from db1.b) "
                + "insert overwrite table target.c select q.id from q;"
        );

        assertUnknownEmptySource(statement, "missing CTE owner");
    }

    @Test
    public void constantCteProjectionDoesNotEmitUnknownWarning() {
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.b", "id")
                .addTable("target.c", "id"),
            "with q as (select 1 as id from db1.b) insert overwrite table target.c select q.id from q;"
        );

        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "constant CTE lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), "constant CTE sources");
        for (LineageWarning warning : statement.getWarnings()) {
            TestSupport.assertTrue(
                warning.getWarningCode() != WarningCode.UNKNOWN_COLUMN_LINEAGE,
                "constant CTE should not emit unknown warning"
            );
        }
    }

    @Test
    public void unresolvedSetCteProjectionIsAtomicAcrossOperatorsAndBranchOrder() {
        for (String operator : SET_OPERATORS) {
            assertAtomicUnresolvedSetProjection(operator, true);
            assertAtomicUnresolvedSetProjection(operator, false);
        }
    }

    @Test
    public void fullyResolvedSetCteProjectionMergesBranchSources() {
        for (String operator : SET_OPERATORS) {
            StatementLineageResult statement = analyze(
                new MapMetadataProvider()
                    .addTable("db1.a", "id")
                    .addTable("db2.b", "id")
                    .addTable("target.c", "id"),
                "with q as (select a.id as id from db1.a a " + operator
                    + " select b.id as id from db2.b b) "
                    + "insert overwrite table target.c select q.id from q;"
            );

            TestSupport.assertEquals(1, statement.getColumnLineages().size(), operator + " resolved lineage count");
            TestSupport.assertTrue(
                containsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "db1.a.id"),
                operator + " resolved first source; expression="
                    + statement.getColumnLineages().get(0).getExpression()
                    + ", warnings=" + statement.getWarnings()
            );
            assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "db2.b.id");
            assertNoUnknownWarning(statement, operator + " resolved projection");
        }
    }

    @Test
    public void constantSetCteProjectionDoesNotEmitUnknownWarning() {
        for (String operator : SET_OPERATORS) {
            StatementLineageResult statement = analyze(
                new MapMetadataProvider()
                    .addTable("db1.a", "id")
                    .addTable("target.c", "id"),
                "with q as (select 1 as id from db1.a " + operator
                    + " select 2 as id from db1.a) "
                    + "insert overwrite table target.c select q.id from q;"
            );

            TestSupport.assertEquals(1, statement.getColumnLineages().size(), operator + " constant lineage count");
            TestSupport.assertEquals(
                0,
                statement.getColumnLineages().get(0).getSourceColumns().size(),
                operator + " constant sources"
            );
            assertNoUnknownWarning(statement, operator + " constant projection");
        }
    }

    private void assertAtomicUnresolvedSetProjection(String operator, boolean unresolvedFirst) {
        String unresolved = "select missing.id as id from db1.a";
        String resolved = "select a.id as id from db1.a a";
        String branches = unresolvedFirst
            ? unresolved + " " + operator + " " + resolved
            : resolved + " " + operator + " " + unresolved;
        StatementLineageResult statement = analyze(
            new MapMetadataProvider()
                .addTable("db1.a", "id")
                .addTable("target.c", "id"),
            "with q as (" + branches + ") "
                + "insert overwrite table target.c select q.id from q;"
        );

        String message = operator + (unresolvedFirst ? " first" : " second") + " unresolved";
        assertUnknownEmptySource(statement, message);
    }

    private StatementLineageResult analyze(MapMetadataProvider metadata, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_scoped_cte_warning")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private void assertUnknownEmptySource(StatementLineageResult statement, String message) {
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), message + " lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), message + " sources");
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
