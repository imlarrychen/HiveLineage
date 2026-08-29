package com.panfan.hivelineage;

import com.panfan.hivelineage.core.*;
import com.panfan.hivelineage.metadata.*;
import com.panfan.hivelineage.request.*;
import com.panfan.hivelineage.result.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.*;

public class HiveJoinLineageTest {
    @Test
    public void parseJoinConditionsDefaultOffKeepsPureQueryIgnored() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_off")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("select * from ods.t1 a left join ods.t2 b on a.id = b.id;")
            .options(LineageAnalyzeOptions.builder().build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getStatements().size(), "join parse default off statement count");
        TestSupport.assertEquals(1, result.getIgnoredStatementCount(), "join parse default off ignored count");
    }

    @Test
    public void pureQueryJoinConditionIsReturnedWithoutFormalLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_001")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("select * from ods.t1 a left join ods.t2 b on a.id = b.id;")
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().size(), "join condition statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "join condition parse status");
        TestSupport.assertTrue(!statement.isFormalLineageProduced(), "pure query should not produce formal lineage");
        TestSupport.assertTrue(statement.getTableLineages().isEmpty(), "pure query table lineage");
        TestSupport.assertTrue(statement.getColumnLineages().isEmpty(), "pure query column lineage");
        TestSupport.assertEquals(1, statement.getJoinConditions().size(), "join condition count");

        JoinCondition condition = statement.getJoinConditions().get(0);
        TestSupport.assertEquals(1, condition.getJoinIndex(), "join index");
        TestSupport.assertEquals("root", condition.getQueryBlockPath(), "query block path");
        TestSupport.assertEquals(2, condition.getJoinType(), "join type");
        TestSupport.assertEquals("LEFT_JOIN", condition.getJoinTypeName(), "join type name");
        TestSupport.assertEquals("a.id = b.id", condition.getJoinExpression(), "join expression");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.t1");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.t2");
        assertContainsColumn(condition.getDirectJoinColumns(), "ods.t1.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "ods.t2.id");
        assertContainsSource(condition.getJoinTables(), "ods.t1");
        assertContainsSource(condition.getJoinTables(), "ods.t2");
        assertContainsColumn(condition.getJoinColumns(), "ods.t1.id");
        assertContainsColumn(condition.getJoinColumns(), "ods.t2.id");
    }

    @Test
    public void joinConditionKeepsExpressionAndDirectColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_002")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "select a.log_id, b.meta_name "
                    + "from ods_op_logs a "
                    + "left join dim_meta_info b "
                    + "on split(a.biz_trace_id, '_')[1] = cast(b.meta_id as string) "
                    + "where a.dt = '20260710' and b.dt = '20260710';"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        JoinCondition condition = statement.getJoinConditions().get(0);

        TestSupport.assertEquals("LEFT_JOIN", condition.getJoinTypeName(), "expression join type");
        TestSupport.assertEquals(
            "split(a.biz_trace_id, '_')[1] = cast(b.meta_id as string)",
            condition.getJoinExpression(),
            "expression join condition"
        );
        assertContainsSource(condition.getDirectJoinObjects(), "default.ods_op_logs");
        assertContainsSource(condition.getDirectJoinObjects(), "default.dim_meta_info");
        assertContainsColumn(condition.getDirectJoinColumns(), "default.ods_op_logs.biz_trace_id");
        assertContainsColumn(condition.getDirectJoinColumns(), "default.dim_meta_info.meta_id");
        assertContainsSource(condition.getJoinTables(), "default.ods_op_logs");
        assertContainsSource(condition.getJoinTables(), "default.dim_meta_info");
        assertContainsColumn(condition.getJoinColumns(), "default.ods_op_logs.biz_trace_id");
        assertContainsColumn(condition.getJoinColumns(), "default.dim_meta_info.meta_id");
        TestSupport.assertTrue(!containsColumn(condition.getDirectJoinColumns(), "a.dt"), "where column should not be direct join column");
        TestSupport.assertTrue(!containsColumn(condition.getDirectJoinColumns(), "b.dt"), "where column should not be direct join column");
    }

    @Test
    public void unqualifiedJoinColumnsUseMetadataToResolveOwners() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        TestMetadataProvider metadataProvider = new TestMetadataProvider()
            .addTable("ods.join_left", "user_id", "name")
            .addTable("ods.join_right", "manager_id", "dept_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_unqualified")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("select * from ods.join_left l join ods.join_right r on user_id = manager_id;")
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        JoinCondition condition = statement.getJoinConditions().get(0);

        TestSupport.assertEquals("user_id = manager_id", condition.getJoinExpression(), "unqualified join expression");
        assertContainsColumn(condition.getDirectJoinColumns(), "user_id");
        assertContainsColumn(condition.getDirectJoinColumns(), "manager_id");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.join_left");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.join_right");
        assertContainsSource(condition.getJoinTables(), "ods.join_left");
        assertContainsSource(condition.getJoinTables(), "ods.join_right");
        assertContainsColumn(condition.getJoinColumns(), "ods.join_left.user_id");
        assertContainsColumn(condition.getJoinColumns(), "ods.join_right.manager_id");
    }

    @Test
    public void unqualifiedImplicitWhereJoinColumnsUseMetadataToResolveOwners() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        TestMetadataProvider metadataProvider = new TestMetadataProvider()
            .addTable("ods.join_left", "user_id", "name")
            .addTable("ods.join_right", "manager_id", "dept_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_where_unqualified")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("select * from ods.join_left l, ods.join_right r where user_id = manager_id;")
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        JoinCondition condition = statement.getJoinConditions().get(0);

        TestSupport.assertEquals("IMPLICIT_JOIN", condition.getJoinTypeName(), "unqualified implicit join type");
        TestSupport.assertEquals("user_id = manager_id", condition.getJoinExpression(), "unqualified implicit join expression");
        assertContainsColumn(condition.getDirectJoinColumns(), "user_id");
        assertContainsColumn(condition.getDirectJoinColumns(), "manager_id");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.join_left");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.join_right");
        assertContainsColumn(condition.getJoinColumns(), "ods.join_left.user_id");
        assertContainsColumn(condition.getJoinColumns(), "ods.join_right.manager_id");
    }

    @Test
    public void consecutiveJoinConditionsAreReturnedInOneStatement() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_multi")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "select * from ods.a a "
                    + "join ods.b b on a.id = b.id "
                    + "left join ods.c c on b.cid = c.id and c.dt = '20260710';"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(2, statement.getJoinConditions().size(), "consecutive join condition count");
        JoinCondition first = statement.getJoinConditions().get(0);
        TestSupport.assertEquals(1, first.getJoinIndex(), "first join index");
        TestSupport.assertEquals("a.id = b.id", first.getJoinExpression(), "first join expression");
        assertContainsColumn(first.getJoinColumns(), "ods.a.id");
        assertContainsColumn(first.getJoinColumns(), "ods.b.id");

        JoinCondition second = statement.getJoinConditions().get(1);
        TestSupport.assertEquals(2, second.getJoinIndex(), "second join index");
        TestSupport.assertEquals("LEFT_JOIN", second.getJoinTypeName(), "second join type");
        TestSupport.assertEquals("b.cid = c.id and c.dt = '20260710'", second.getJoinExpression(), "second join expression");
        assertContainsColumn(second.getDirectJoinColumns(), "ods.b.cid");
        assertContainsColumn(second.getDirectJoinColumns(), "ods.c.id");
        assertContainsColumn(second.getDirectJoinColumns(), "ods.c.dt");
        assertContainsColumn(second.getJoinColumns(), "ods.b.cid");
        assertContainsColumn(second.getJoinColumns(), "ods.c.id");
        assertContainsColumn(second.getJoinColumns(), "ods.c.dt");
    }

    @Test
    public void implicitWhereJoinIsReturnedAsJoinCondition() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_where")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("select * from ods.a a, ods.b b where a.id = b.id;")
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().size(), "implicit where join statement count");
        TestSupport.assertEquals(0, result.getIgnoredStatementCount(), "implicit where join ignored count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertTrue(!statement.isFormalLineageProduced(), "implicit where join should not produce formal lineage");
        TestSupport.assertEquals(1, statement.getJoinConditions().size(), "implicit where join condition count");
        JoinCondition condition = statement.getJoinConditions().get(0);
        TestSupport.assertEquals(7, condition.getJoinType(), "implicit where join type");
        TestSupport.assertEquals("IMPLICIT_JOIN", condition.getJoinTypeName(), "implicit where join type name");
        TestSupport.assertEquals("a.id = b.id", condition.getJoinExpression(), "implicit where join expression");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.a");
        assertContainsSource(condition.getDirectJoinObjects(), "ods.b");
        assertContainsColumn(condition.getDirectJoinColumns(), "ods.a.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "ods.b.id");
        assertContainsSource(condition.getJoinTables(), "ods.a");
        assertContainsSource(condition.getJoinTables(), "ods.b");
        assertContainsColumn(condition.getJoinColumns(), "ods.a.id");
        assertContainsColumn(condition.getJoinColumns(), "ods.b.id");
    }

    @Test
    public void implicitWhereJoinDoesNotIncludeSingleTableFilters() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_where_filter")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("select * from ods.a a, ods.b b where a.id = b.id and a.dt = '20260710';")
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().size(), "implicit where filter statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(1, statement.getJoinConditions().size(), "implicit where filter join condition count");
        JoinCondition condition = statement.getJoinConditions().get(0);
        TestSupport.assertEquals("IMPLICIT_JOIN", condition.getJoinTypeName(), "implicit where filter join type");
        TestSupport.assertEquals("a.id = b.id", condition.getJoinExpression(), "single table filter should be excluded");
        TestSupport.assertTrue(!containsColumn(condition.getDirectJoinColumns(), "a.dt"), "single table filter column should be excluded");
    }

    @Test
    public void existsSubqueryJoinConditionIsReturnedWithQueryBlockPath() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_exists")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "select * from ods.a a join ods.b b on a.id = b.id "
                    + "where exists ("
                    + "select 1 from ods.c c join ods.d d on c.id = d.id "
                    + "where c.id = a.id"
                    + ");"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(2, statement.getJoinConditions().size(), "exists join condition count");
        JoinCondition rootJoin = statement.getJoinConditions().get(0);
        TestSupport.assertEquals("root", rootJoin.getQueryBlockPath(), "root join path");
        TestSupport.assertEquals("a.id = b.id", rootJoin.getJoinExpression(), "root join expression");

        JoinCondition existsJoin = statement.getJoinConditions().get(1);
        TestSupport.assertEquals("root.where.exists[1]", existsJoin.getQueryBlockPath(), "exists join path");
        TestSupport.assertEquals("c.id = d.id", existsJoin.getJoinExpression(), "exists join expression");
        assertContainsColumn(existsJoin.getJoinColumns(), "ods.c.id");
        assertContainsColumn(existsJoin.getJoinColumns(), "ods.d.id");
        TestSupport.assertTrue(!containsColumn(existsJoin.getDirectJoinColumns(), "a.id"), "outer where correlation should not be join column");
    }

    @Test
    public void unresolvedCteJoinColumnDoesNotBecomePhysicalJoinColumn() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_unresolved_cte")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "with x as ("
                    + "select * from ods.a a join ods.b b on a.id = b.id"
                    + "), y as ("
                    + "select id from ods.c"
                    + ") "
                    + "select * from x join y on x.id = y.id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        JoinCondition rootJoin = findJoinCondition(statement, "x.id = y.id");

        TestSupport.assertEquals("x.id = y.id", rootJoin.getJoinExpression(), "unresolved cte root expression");
        assertContainsColumn(rootJoin.getDirectJoinColumns(), "x.id");
        assertContainsColumn(rootJoin.getDirectJoinColumns(), "y.id");
        TestSupport.assertTrue(!containsColumn(rootJoin.getJoinColumns(), "x.id"), "unresolved cte column should not be physical join column");
        TestSupport.assertTrue(!containsSource(rootJoin.getJoinTables(), "x"), "unresolved cte should not be physical join table");
        assertContainsColumn(rootJoin.getJoinColumns(), "ods.c.id");
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
    }

    @Test
    public void cteJoinConditionSeparatesDirectAndPhysicalObjects() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_003")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "with x as ("
                    + "select a.id as id from ods.a a join ods.b b on a.id = b.id"
                    + "), y as ("
                    + "select c.id as id from ods.c c join ods.d d on c.id = d.id"
                    + ") "
                    + "select * from x join y on x.id = y.id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        TestSupport.assertEquals(3, statement.getJoinConditions().size(), "cte join condition count");

        JoinCondition rootJoin = statement.getJoinConditions().get(2);
        TestSupport.assertEquals(3, rootJoin.getJoinIndex(), "root cte join index");
        TestSupport.assertEquals("root", rootJoin.getQueryBlockPath(), "root cte query block path");
        TestSupport.assertEquals("x.id = y.id", rootJoin.getJoinExpression(), "root cte expression");
        assertContainsSource(rootJoin.getDirectJoinObjects(), "x");
        assertContainsSource(rootJoin.getDirectJoinObjects(), "y");
        assertContainsColumn(rootJoin.getDirectJoinColumns(), "x.id");
        assertContainsColumn(rootJoin.getDirectJoinColumns(), "y.id");
        assertContainsSource(rootJoin.getJoinTables(), "ods.a");
        assertContainsSource(rootJoin.getJoinTables(), "ods.c");
        assertContainsColumn(rootJoin.getJoinColumns(), "ods.a.id");
        assertContainsColumn(rootJoin.getJoinColumns(), "ods.c.id");
    }

    @Test
    public void temporaryTableJoinConditionExpandsToPhysicalColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_004")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "create temporary table tmp_user as "
                    + "select user_id, dept_id from ods.user_base; "
                    + "select * from tmp_user t join dim.dept d on t.dept_id = d.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseJoinConditions(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().size(), "temporary join returned statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        JoinCondition condition = statement.getJoinConditions().get(0);
        TestSupport.assertEquals("t.dept_id = d.dept_id", condition.getJoinExpression(), "temporary join expression");
        assertContainsSource(condition.getDirectJoinObjects(), "tmp_user");
        assertContainsSource(condition.getDirectJoinObjects(), "dim.dept");
        assertContainsColumn(condition.getDirectJoinColumns(), "t.dept_id");
        assertContainsColumn(condition.getDirectJoinColumns(), "dim.dept.dept_id");
        assertContainsSource(condition.getJoinTables(), "ods.user_base");
        assertContainsSource(condition.getJoinTables(), "dim.dept");
        assertContainsColumn(condition.getJoinColumns(), "ods.user_base.dept_id");
        assertContainsColumn(condition.getJoinColumns(), "dim.dept.dept_id");
    }
}
