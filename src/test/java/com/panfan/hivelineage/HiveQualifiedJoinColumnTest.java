package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsSource;

public class HiveQualifiedJoinColumnTest {
    @Test
    public void databaseQualifiedExplicitJoinColumnsKeepFullOwners() {
        JoinCondition condition = analyzeJoin(
            "select whc.a.field1 from whc.a left join whc.b "
                + "on whc.a.id = whc.b.id"
        ).getJoinConditions().get(0);

        assertContainsColumn(condition.getDirectJoinColumns(), "whc.a.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "whc.b.id");
        assertContainsSource(condition.getDirectJoinObjects(), "whc.a");
        assertContainsSource(condition.getDirectJoinObjects(), "whc.b");
        assertContainsSource(condition.getJoinTables(), "whc.a");
        assertContainsSource(condition.getJoinTables(), "whc.b");
        assertContainsColumn(condition.getJoinColumns(), "whc.a.id");
        assertContainsColumn(condition.getJoinColumns(), "whc.b.id");
    }

    @Test
    public void databaseQualifiedImplicitJoinColumnsKeepFullOwners() {
        StatementLineageResult statement = analyzeJoin(
            "select * from whc.a, whc.b where whc.a.id = whc.b.id"
        );
        TestSupport.assertEquals(1, statement.getJoinConditions().size(), "qualified implicit join count");
        JoinCondition condition = statement.getJoinConditions().get(0);

        TestSupport.assertEquals("IMPLICIT_JOIN", condition.getJoinTypeName(), "qualified implicit join type");
        assertContainsColumn(condition.getDirectJoinColumns(), "whc.a.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "whc.b.id");
        assertContainsSource(condition.getJoinTables(), "whc.a");
        assertContainsSource(condition.getJoinTables(), "whc.b");
    }

    @Test
    public void twoPartAliasJoinColumnsRemainResolved() {
        JoinCondition condition = analyzeJoin(
            "select a.field1 from whc.a a left join whc.b b on a.id = b.id"
        ).getJoinConditions().get(0);

        assertContainsColumn(condition.getDirectJoinColumns(), "whc.a.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "whc.b.id");
        assertContainsColumn(condition.getJoinColumns(), "whc.a.id");
        assertContainsColumn(condition.getJoinColumns(), "whc.b.id");
    }

    @Test
    public void unknownThreePartJoinOwnerDoesNotBecomePhysicalSource() {
        StatementLineageResult statement = analyzeJoin(
            "select a.field1 from whc.a a left join whc.b b "
                + "on missing.a.id = b.id"
        );
        JoinCondition condition = statement.getJoinConditions().get(0);

        assertContainsColumn(condition.getDirectJoinColumns(), "missing.a.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "whc.b.id");
        TestSupport.assertTrue(
            !containsColumn(condition.getJoinColumns(), "missing.a.id"),
            "unknown qualified join owner should not become physical column"
        );
        TestSupport.assertTrue(
            !containsSource(condition.getJoinTables(), "missing.a"),
            "unknown qualified join owner should not become physical table"
        );
        assertContainsColumn(condition.getJoinColumns(), "whc.b.id");
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
    }

    @Test
    public void unresolvedLogicalJoinObjectsRemainDirectOnly() {
        StatementLineageResult statement = analyzeJoin(
            "select ??? from (select 1) x join db1.t y on x.id=y.id"
        );
        JoinCondition condition = statement.getJoinConditions().get(0);

        assertContainsSource(condition.getDirectJoinObjects(), "x");
        assertContainsColumn(condition.getDirectJoinColumns(), "x.id");
        TestSupport.assertTrue(
            !containsSource(condition.getJoinTables(), "x"),
            "unresolved logical join object should remain outside physical join tables"
        );
        assertContainsSource(condition.getJoinTables(), "db1.t");
        assertContainsColumn(condition.getJoinColumns(), "db1.t.id");
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
    }

    @Test
    public void logicalDirectObjectIsFilteredWhenAllJoinColumnsAreUnresolved() {
        StatementLineageResult statement = analyzeJoin(
            "select * from (select 1) x join db1.t y on x.id=x.other_id"
        );
        JoinCondition condition = statement.getJoinConditions().get(0);

        assertContainsSource(condition.getDirectJoinObjects(), "x");
        assertContainsColumn(condition.getDirectJoinColumns(), "x.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "x.other_id");
        TestSupport.assertEquals(0, condition.getJoinColumns().size(), "unresolved join columns");
        TestSupport.assertTrue(
            !containsSource(condition.getJoinTables(), "x"),
            "logical direct object should remain outside physical join tables"
        );
        TestSupport.assertEquals(0, condition.getJoinTables().size(), "unresolved physical join tables");
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
    }

    private StatementLineageResult analyzeJoin(String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_qualified_join")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(sql + ";")
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }
}
