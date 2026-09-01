package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.findJoinCondition;

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

    @Test
    public void sessionDatabaseBindsPhysicalSourcesBehindCteJoinAlias() {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_session_cte")
            .taskVersion("V1")
            .defaultDatabase("db1")
            .rawScript(
                "with c as (select a.id from base a) "
                    + "select * from c x join db2.t2 y on x.id = y.id;"
            )
            .options(LineageAnalyzeOptions.builder().parseJoinConditions(true).build())
            .build();

        StatementLineageResult statement = new HiveScriptLineageAnalyzer()
            .analyzeScript(request)
            .getStatements()
            .get(0);
        JoinCondition condition = findJoinCondition(statement, "x.id = y.id");

        assertContainsSource(condition.getDirectJoinObjects(), "x");
        assertContainsColumn(condition.getDirectJoinColumns(), "x.id");
        assertContainsSource(condition.getJoinTables(), "db1.base");
        assertContainsSource(condition.getJoinTables(), "db2.t2");
        assertContainsColumn(condition.getJoinColumns(), "db1.base.id");
        assertContainsColumn(condition.getJoinColumns(), "db2.t2.id");
        TestSupport.assertTrue(
            statement.getWarnings().stream().noneMatch(
                warning -> warning.getWarningCode() == WarningCode.UNKNOWN_COLUMN_LINEAGE
                    && warning.getMessage().contains(" x")
            ),
            "bound CTE join alias should not emit an unknown x warning"
        );
    }

    @Test
    public void aliasesAreResolvedWithinTheirOwnCteAndRootQueryBlocks() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "db1",
            "with c as (select i.id from db1.inner1 i join db1.inner2 j on i.id = j.id) "
                + "select * from c i join db2.outer_t j on i.id = j.id"
        );

        JoinCondition inner = findJoinByPath(statement, "cte.c");
        assertContainsColumn(inner.getDirectJoinColumns(), "db1.inner1.id");
        assertContainsColumn(inner.getDirectJoinColumns(), "db1.inner2.id");
        assertContainsSource(inner.getJoinTables(), "db1.inner1");
        assertContainsSource(inner.getJoinTables(), "db1.inner2");
        assertContainsColumn(inner.getJoinColumns(), "db1.inner1.id");
        assertContainsColumn(inner.getJoinColumns(), "db1.inner2.id");

        JoinCondition outer = findJoinByPath(statement, "root");
        assertContainsSource(outer.getDirectJoinObjects(), "i");
        assertContainsColumn(outer.getDirectJoinColumns(), "i.id");
        assertContainsColumn(outer.getDirectJoinColumns(), "db2.outer_t.id");
        assertContainsSource(outer.getJoinTables(), "db1.inner1");
        assertContainsSource(outer.getJoinTables(), "db2.outer_t");
        assertContainsColumn(outer.getJoinColumns(), "db1.inner1.id");
        assertContainsColumn(outer.getJoinColumns(), "db2.outer_t.id");
    }

    @Test
    public void qualifiedPhysicalTableDoesNotCollideWithSameNamedCte() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "db1",
            "with t as (select id from db2.t) "
                + "select * from t join db1.t x on t.id = x.id"
        );
        JoinCondition condition = findJoinByPath(statement, "root");

        assertContainsSource(condition.getDirectJoinObjects(), "t");
        assertContainsColumn(condition.getDirectJoinColumns(), "t.id");
        assertContainsColumn(condition.getDirectJoinColumns(), "db1.t.id");
        assertContainsSource(condition.getJoinTables(), "db2.t");
        assertContainsSource(condition.getJoinTables(), "db1.t");
        assertContainsColumn(condition.getJoinColumns(), "db2.t.id");
        assertContainsColumn(condition.getJoinColumns(), "db1.t.id");
    }

    @Test
    public void uniqueQualifiedSourceStillSupportsItsSimpleName() {
        JoinCondition condition = findJoinByPath(
            analyzeJoinWithDatabase(
                "default",
                "select * from db1.t join db2.other on t.id = other.id"
            ),
            "root"
        );

        assertContainsColumn(condition.getJoinColumns(), "db1.t.id");
        assertContainsColumn(condition.getJoinColumns(), "db2.other.id");
    }

    @Test
    public void ambiguousSimpleNameAcrossQualifiedSourcesIsNotArbitrarilyBound() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "default",
            "select * from db1.t join db2.t on t.id = db2.t.id"
        );
        JoinCondition condition = findJoinByPath(statement, "root");

        TestSupport.assertTrue(
            !containsColumn(condition.getJoinColumns(), "db1.t.id"),
            "ambiguous t.id must not be assigned to db1.t"
        );
        assertContainsColumn(condition.getJoinColumns(), "db2.t.id");
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
    }

    @Test
    public void explicitAliasWinsOverCollidingQualifiedSimpleName() {
        JoinCondition condition = findJoinByPath(
            analyzeJoinWithDatabase(
                "default",
                "select * from db1.base t join db2.t on t.id = db2.t.id"
            ),
            "root"
        );

        assertContainsColumn(condition.getJoinColumns(), "db1.base.id");
        assertContainsColumn(condition.getJoinColumns(), "db2.t.id");
    }

    @Test
    public void repeatedAliasesInSiblingExistsSubqueriesStayInTheirAstScopes() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "default",
            "select * from db1.a a join db1.b b on a.id = b.id "
                + "where exists (select 1 from db2.c i join db2.d j on i.id = j.id) "
                + "and exists (select 1 from db3.e i join db3.f j on i.id = j.id)"
        );

        assertPhysicalJoin(findJoinByPath(statement, "root"), "db1.a", "db1.b");
        assertPhysicalJoin(
            findJoinByPath(statement, "root.where.exists[1]"),
            "db2.c",
            "db2.d"
        );
        assertPhysicalJoin(
            findJoinByPath(statement, "root.where.exists[2]"),
            "db3.e",
            "db3.f"
        );
    }

    @Test
    public void nestedExistsSubqueryUsesNestedAstScopePath() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "default",
            "select * from db1.a a join db1.b b on a.id = b.id "
                + "where exists (select 1 from db2.c i join db2.d j on i.id = j.id "
                + "where exists (select 1 from db3.e i join db3.f j on i.id = j.id))"
        );

        assertPhysicalJoin(
            findJoinByPath(statement, "root.where.exists[1]"),
            "db2.c",
            "db2.d"
        );
        assertPhysicalJoin(
            findJoinByPath(statement, "root.where.exists[1].where.exists[1]"),
            "db3.e",
            "db3.f"
        );
    }

    @Test
    public void inlineSubqueryAndExistsEachProduceOneScopedJoin() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "default",
            "select * from (select i.id from db1.a i join db1.b j on i.id=j.id) s "
                + "join db1.c c on s.id=c.id "
                + "where exists (select 1 from db2.d i join db2.e j on i.id=j.id)"
        );

        TestSupport.assertEquals(3, statement.getJoinConditions().size(), "scoped join count");

        JoinCondition root = findJoinByPath(statement, "root");
        TestSupport.assertEquals(1, root.getJoinIndex(), "root join index");
        assertContainsSource(root.getJoinTables(), "db1.a");
        assertContainsSource(root.getJoinTables(), "db1.c");
        assertContainsColumn(root.getJoinColumns(), "db1.a.id");
        assertContainsColumn(root.getJoinColumns(), "db1.c.id");

        JoinCondition inline = findJoinByPath(statement, "root.subquery[1]");
        TestSupport.assertEquals(2, inline.getJoinIndex(), "inline subquery join index");
        assertPhysicalJoin(inline, "db1.a", "db1.b");

        JoinCondition exists = findJoinByPath(statement, "root.where.exists[2]");
        TestSupport.assertEquals(3, exists.getJoinIndex(), "exists subquery join index");
        assertPhysicalJoin(exists, "db2.d", "db2.e");
    }

    @Test
    public void identicalSubqueryTextWithDistinctAstNodesIsPreserved() {
        StatementLineageResult statement = analyzeJoinWithDatabase(
            "default",
            "select * from (select i.id from db1.a i join db1.b j on i.id=j.id) s1 "
                + "join (select i.id from db1.a i join db1.b j on i.id=j.id) s2 "
                + "on s1.id=s2.id"
        );

        TestSupport.assertEquals(3, statement.getJoinConditions().size(), "identical subquery join count");
        TestSupport.assertEquals(1, countJoinsByPath(statement, "root.subquery[1]"), "first subquery join count");
        TestSupport.assertEquals(1, countJoinsByPath(statement, "root.subquery[2]"), "second subquery join count");
        TestSupport.assertEquals(1, countJoinsByPath(statement, "root"), "root join count");
        assertPhysicalJoin(findJoinByPath(statement, "root.subquery[1]"), "db1.a", "db1.b");
        assertPhysicalJoin(findJoinByPath(statement, "root.subquery[2]"), "db1.a", "db1.b");
    }

    private void assertPhysicalJoin(JoinCondition condition, String leftTable, String rightTable) {
        assertContainsSource(condition.getDirectJoinObjects(), leftTable);
        assertContainsSource(condition.getDirectJoinObjects(), rightTable);
        assertContainsColumn(condition.getDirectJoinColumns(), leftTable + ".id");
        assertContainsColumn(condition.getDirectJoinColumns(), rightTable + ".id");
        assertContainsSource(condition.getJoinTables(), leftTable);
        assertContainsSource(condition.getJoinTables(), rightTable);
        assertContainsColumn(condition.getJoinColumns(), leftTable + ".id");
        assertContainsColumn(condition.getJoinColumns(), rightTable + ".id");
    }

    private StatementLineageResult analyzeJoinWithDatabase(String database, String sql) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_join")
            .taskId("task_join_scope")
            .taskVersion("V1")
            .defaultDatabase(database)
            .rawScript(sql + ";")
            .options(LineageAnalyzeOptions.builder().parseJoinConditions(true).build())
            .build();
        return new HiveScriptLineageAnalyzer().analyzeScript(request).getStatements().get(0);
    }

    private JoinCondition findJoinByPath(StatementLineageResult statement, String queryBlockPath) {
        for (JoinCondition condition : statement.getJoinConditions()) {
            if (queryBlockPath.equals(condition.getQueryBlockPath())) {
                return condition;
            }
        }
        throw new AssertionError("Expected join query block " + queryBlockPath);
    }

    private int countJoinsByPath(StatementLineageResult statement, String queryBlockPath) {
        int count = 0;
        for (JoinCondition condition : statement.getJoinConditions()) {
            if (queryBlockPath.equals(condition.getQueryBlockPath())) {
                count++;
            }
        }
        return count;
    }
}
