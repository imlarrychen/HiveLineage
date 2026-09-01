package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.MapMetadataProvider;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertNotContainsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.containsColumn;

public class HiveColumnLineageTest {
    @Test
    public void expressionColumnLineageRecordsSourceColumnsAndExpression() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_001")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert into test_a(a1, a2) select b1 + b2, b3 from test_b;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(1, result.getStatements().size(), "statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals(0, statement.getParseStatusCode(), "parse status");
        TestSupport.assertTrue(statement.isFormalLineageProduced(), "formal lineage produced");
        TestSupport.assertEquals(1, statement.getTableLineages().size(), "table lineage count");
        TestSupport.assertEquals("default.test_b", statement.getTableLineages().get(0).getSourceObjects().get(0).getQualifiedName(), "source table");
        TestSupport.assertEquals("default.test_a", statement.getTableLineages().get(0).getTargetObject().getQualifiedName(), "target table");

        List<ColumnLineage> columns = statement.getColumnLineages();
        TestSupport.assertEquals(2, columns.size(), "column lineage count");

        ColumnLineage a1 = columns.get(0);
        TestSupport.assertEquals("default.test_a.a1", a1.getTargetColumn().getQualifiedName(), "a1 target");
        TestSupport.assertEquals(LineageType.DERIVED, a1.getLineageType(), "a1 lineage type");
        TestSupport.assertEquals("b1 + b2", a1.getExpression(), "a1 expression");
        TestSupport.assertTrue(a1.isExpressionExtracted(), "a1 expression extracted");
        TestSupport.assertEquals("default.test_b.b1", a1.getSourceColumns().get(0).getQualifiedName(), "a1 first source column");
        TestSupport.assertEquals("default.test_b.b2", a1.getSourceColumns().get(1).getQualifiedName(), "a1 second source column");

        ColumnLineage a2 = columns.get(1);
        TestSupport.assertEquals("default.test_a.a2", a2.getTargetColumn().getQualifiedName(), "a2 target");
        TestSupport.assertEquals(LineageType.DIRECT, a2.getLineageType(), "a2 lineage type");
        TestSupport.assertEquals("b3", a2.getExpression(), "a2 expression");
        TestSupport.assertEquals("default.test_b.b3", a2.getSourceColumns().get(0).getQualifiedName(), "a2 source column");
    }

    @Test
    public void unionAllColumnLineageUsesBranchSelectColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.union_source_a", "id", "name")
            .addTable("ods.union_source_b", "id", "name")
            .addTable("dwd.union_target", "id", "name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_union")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.union_target "
                    + "select id, name from ods.union_source_a "
                    + "union all "
                    + "select id, name from ods.union_source_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        List<ColumnLineage> columns = statement.getColumnLineages();

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "union parse status");
        TestSupport.assertEquals(2, columns.size(), "union column lineage count");
        TestSupport.assertEquals(
            "dwd.union_target.id",
            columns.get(0).getTargetColumn().getQualifiedName(),
            "union id target"
        );
        TestSupport.assertEquals(
            "union_all(ods.union_source_a.id, ods.union_source_b.id)",
            columns.get(0).getExpression(),
            "union id expression"
        );
        assertContainsColumn(columns.get(0).getSourceColumns(), "ods.union_source_a.id");
        assertContainsColumn(columns.get(0).getSourceColumns(), "ods.union_source_b.id");
        TestSupport.assertEquals(
            "dwd.union_target.name",
            columns.get(1).getTargetColumn().getQualifiedName(),
            "union name target"
        );
        TestSupport.assertEquals(
            "union_all(ods.union_source_a.name, ods.union_source_b.name)",
            columns.get(1).getExpression(),
            "union name expression"
        );
        assertContainsColumn(columns.get(1).getSourceColumns(), "ods.union_source_a.name");
        assertContainsColumn(columns.get(1).getSourceColumns(), "ods.union_source_b.name");
    }

    @Test
    public void nestedUnionAllColumnLineageMergesBranchColumnsThroughSubquery() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.vrf_t", "vrf_clt_nbr", "vrf_iac_nbr", "vrf_chk_rlt", "biz_date")
            .addTable("ods.vrf_h", "cust_id", "iac_id", "vrf_rst_cd", "dw_upd_tm", "biz_date")
            .addTable("dwd.vrf_target", "cust_id", "iac_id", "vrf_rst_cd", "dw_upd_tm");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_nested_union")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.vrf_target(cust_id, iac_id, vrf_rst_cd, dw_upd_tm) "
                    + "select cust_id, iac_id, vrf_rst_cd, dw_upd_tm "
                    + "from ("
                    + "  select cust_id, iac_id, vrf_rst_cd, dw_upd_tm, "
                    + "         row_number() over(partition by cust_id order by dw_upd_tm desc) as rn "
                    + "  from ("
                    + "    select vrf_clt_nbr as cust_id, vrf_iac_nbr as iac_id, "
                    + "           vrf_chk_rlt as vrf_rst_cd, current_timestamp() as dw_upd_tm "
                    + "    from ods.vrf_t "
                    + "    union all "
                    + "    select cust_id, iac_id, vrf_rst_cd, dw_upd_tm from ods.vrf_h"
                    + "  ) t"
                    + ") t0 where t0.rn = 1;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        ColumnLineage custId = statement.getColumnLineages().get(0);
        ColumnLineage updateTime = statement.getColumnLineages().get(3);

        assertContainsColumn(custId.getSourceColumns(), "ods.vrf_t.vrf_clt_nbr");
        assertContainsColumn(custId.getSourceColumns(), "ods.vrf_h.cust_id");
        TestSupport.assertEquals(
            "union_all(ods.vrf_t.vrf_clt_nbr, ods.vrf_h.cust_id)",
            custId.getExpression(),
            "nested union cust_id expression"
        );
        assertContainsColumn(updateTime.getSourceColumns(), "ods.vrf_h.dw_upd_tm");
        TestSupport.assertTrue(
            !containsColumn(updateTime.getSourceColumns(), "ods.vrf_t.dw_upd_tm"),
            "current_timestamp branch should not invent source column"
        );
        TestSupport.assertEquals(
            "union_all(current_timestamp(), ods.vrf_h.dw_upd_tm)",
            updateTime.getExpression(),
            "nested union dw_upd_tm expression"
        );
    }

    @Test
    public void plainUnionColumnLineageUsesDistinctExpression() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.union_source_a", "id")
            .addTable("ods.union_source_b", "id")
            .addTable("dwd.union_target", "id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_union_distinct")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.union_target "
                    + "select id from ods.union_source_a "
                    + "union "
                    + "select id from ods.union_source_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        ColumnLineage column = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(0);

        TestSupport.assertEquals("dwd.union_target.id", column.getTargetColumn().getQualifiedName(), "plain union target");
        TestSupport.assertEquals("union_distinct(ods.union_source_a.id, ods.union_source_b.id)", column.getExpression(), "plain union expression");
        assertContainsColumn(column.getSourceColumns(), "ods.union_source_a.id");
        assertContainsColumn(column.getSourceColumns(), "ods.union_source_b.id");
    }

    @Test
    public void nestedUnionInCteDoesNotOverrideTopLevelSelectLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.union_source_a", "id", "code")
            .addTable("ods.union_source_b", "id", "code")
            .addTable("ods.user_base", "user_id", "user_name")
            .addTable("dwd.final_target", "id", "user_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_nested_union_cte")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "with tmp_u as ("
                    + "select id, code from ods.union_source_a "
                    + "union all "
                    + "select id, code from ods.union_source_b"
                    + ") "
                    + "insert overwrite table dwd.final_target "
                    + "select u.user_id, u.user_name "
                    + "from ods.user_base u "
                    + "left join tmp_u t on u.user_id = t.id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "nested union cte parse status");
        TestSupport.assertEquals("dwd.final_target.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(), "target id");
        TestSupport.assertEquals("u.user_id", statement.getColumnLineages().get(0).getExpression(), "top-level id expression");
        TestSupport.assertEquals(1, statement.getColumnLineages().get(0).getSourceColumns().size(), "target id source count");
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "ods.user_base.user_id");
        TestSupport.assertEquals(
            "dwd.final_target.user_name",
            statement.getColumnLineages().get(1).getTargetColumn().getQualifiedName(),
            "target user_name"
        );
        TestSupport.assertEquals(1, statement.getColumnLineages().get(1).getSourceColumns().size(), "target user_name source count");
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.user_base.user_name");
    }

    @Test
    public void nestedAliasDoesNotOverrideTopLevelAliasWithSameName() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.inner_source", "id")
            .addTable("ods.user_base", "user_id")
            .addTable("dwd.alias_target", "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_alias_scope")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "with tmp_alias as (select t1.id from ods.inner_source t1) "
                    + "insert overwrite table dwd.alias_target "
                    + "select t1.user_id "
                    + "from ods.user_base t1 "
                    + "left join tmp_alias t2 on t1.user_id = t2.id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "alias scope parse status");
        TestSupport.assertEquals("t1.user_id", statement.getColumnLineages().get(0).getExpression(), "top-level alias expression");
        TestSupport.assertEquals(1, statement.getColumnLineages().get(0).getSourceColumns().size(), "top-level alias source count");
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "ods.user_base.user_id");
    }

    @Test
    public void constantSelectItemProducesConstantLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_constant")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite table dwd.constant_target(a1) select 't1' as a1 from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        ColumnLineage column = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(0);

        TestSupport.assertEquals("dwd.constant_target.a1", column.getTargetColumn().getQualifiedName(), "constant target");
        TestSupport.assertEquals(LineageType.CONSTANT, column.getLineageType(), "constant lineage type");
        TestSupport.assertEquals("constant('t1')", column.getExpression(), "constant expression");
        TestSupport.assertTrue(column.getSourceColumns().isEmpty(), "constant source columns");
    }

    @Test
    public void unionAllConstantExpressionsAreMerged() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_union_constant")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert overwrite table dwd.union_constant_target(type_name) "
                    + "select 'A' as type_name from ods.union_source_a "
                    + "union all "
                    + "select 'B' as type_name from ods.union_source_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        ColumnLineage column = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(0);

        TestSupport.assertEquals("dwd.union_constant_target.type_name", column.getTargetColumn().getQualifiedName(), "union constant target");
        TestSupport.assertEquals(LineageType.CONSTANT, column.getLineageType(), "union constant lineage type");
        TestSupport.assertEquals("union_all(constant('A'), constant('B'))", column.getExpression(), "union constant expression");
        TestSupport.assertTrue(column.getSourceColumns().isEmpty(), "union constant source columns");
    }

    @Test
    public void columnLineageResolvesJoinAliasToRealSourceTables() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_011")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "insert into dwd.user_profile(user_id, dept_name) "
                    + "select a.user_id, b.dept_name "
                    + "from ods.user_base a "
                    + "left join ods.user_dept b on a.dept_id = b.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "alias a source column");
        TestSupport.assertEquals("ods.user_dept.dept_name", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "alias b source column");
    }

    @Test
    public void columnLineageUsesSourceMetadataForUnqualifiedJoinColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "dept_id")
            .addTable("ods.user_dept", "dept_id", "dept_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_012")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert into dwd.user_profile(user_id, dept_name) "
                    + "select user_id, dept_name "
                    + "from ods.user_base a "
                    + "left join ods.user_dept b on a.dept_id = b.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(
            "ods.user_base.user_id",
            columns.get(0).getSourceColumns().get(0).getQualifiedName(),
            "metadata user_id source column"
        );
        TestSupport.assertEquals(
            "ods.user_dept.dept_name",
            columns.get(1).getSourceColumns().get(0).getQualifiedName(),
            "metadata dept_name source column"
        );
        TestSupport.assertEquals(2, metadataProvider.getLoadCount(), "source metadata load count");
    }

    @Test
    public void windowFunctionExpressionIsRenderedWithoutAstTokens() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_feature", "user_id", "update_time")
            .addTable("dwd.window_target", "user_id", "rn");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_window")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.window_target(user_id, rn) "
                    + "select user_id, row_number() over(partition by user_id order by update_time desc) as rn "
                    + "from ods.user_feature;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        ColumnLineage rn = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages().get(1);

        TestSupport.assertEquals(
            "row_number() over(partition by user_id order by update_time desc)",
            rn.getExpression(),
            "window expression"
        );
        TestSupport.assertEquals(Collections.singletonList("row_number"), rn.getFunctionNames(), "window function names");
        assertContainsColumn(rn.getSourceColumns(), "ods.user_feature.user_id");
        assertContainsColumn(rn.getSourceColumns(), "ods.user_feature.update_time");
    }

    @Test
    public void createTableAsSelectProducesFormalTableLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_013")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("create table dwd.user_tmp as select user_id, user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "ctas failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "ctas statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals("CREATE_TABLE_AS_SELECT", statement.getStatementType(), "ctas statement type");
        TestSupport.assertEquals("dwd.user_tmp", statement.getOutputObject().getQualifiedName(), "ctas target");
        assertContainsSource(statement, "ods.user_base");
    }

    @Test
    public void commonTableExpressionUsesRealSourcesAndColumnLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.user_profile", "user_id", "dept_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_017")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "with t as (select user_id, dept_id from ods.user_base) "
                    + "insert overwrite table dwd.user_profile "
                    + "select t.user_id, d.dept_name "
                    + "from t join ods.user_dept d on t.dept_id = d.dept_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "cte parse status");
        assertContainsSource(statement, "ods.user_base");
        assertContainsSource(statement, "ods.user_dept");
        assertNotContainsSource(statement, "t");
        TestSupport.assertEquals(
            "dwd.user_profile.user_id",
            statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "cte first target"
        );
        TestSupport.assertEquals(
            "ods.user_base.user_id",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "cte first source"
        );
        TestSupport.assertEquals(
            "ods.user_dept.dept_name",
            statement.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName(),
            "cte second source"
        );
    }

    @Test
    public void commonTableExpressionWithTableAliasExpandsColumnProjection() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.main_eac", "eac_id")
            .addTable("ods.eac_inf", "eac_id", "eac_seq_nbr")
            .addTable("dwd.cte_alias_target", "pre_eac_seq_nbr");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_cte_table_alias")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "with tmp1 as ("
                    + "select eac_id as eaceacshr, eac_seq_nbr as pre_eac_seq_nbr from ods.eac_inf"
                    + ") "
                    + "insert overwrite table dwd.cte_alias_target "
                    + "select t3.pre_eac_seq_nbr "
                    + "from ods.main_eac t1 left join tmp1 t3 on t1.eac_id = t3.eaceacshr;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "cte alias column lineage count");
        ColumnLineage preSeq = statement.getColumnLineages().get(0);

        assertContainsColumn(preSeq.getSourceColumns(), "ods.eac_inf.eac_seq_nbr");
        TestSupport.assertTrue(!containsColumn(preSeq.getSourceColumns(), "tmp1.pre_eac_seq_nbr"), "cte alias should not remain as physical source");
        TestSupport.assertTrue(statement.getWarnings().isEmpty(), "cte alias should not emit tmp metadata warning");
    }

    @Test
    public void dynamicPartitionColumnLineageUsesSelectField() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_020")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite table dwd.user_profile partition(dt) select user_id, dt from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("dwd.user_profile.dt", columns.get(1).getTargetColumn().getQualifiedName(), "dynamic partition target");
        TestSupport.assertEquals("ods.user_base.dt", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "dynamic partition source");
    }

    @Test
    public void staticPartitionColumnLineageRecordsConstantExpression() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_021")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("insert overwrite table dwd.user_profile partition(dt='20260701') select user_id from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(2, columns.size(), "static partition lineage count");
        TestSupport.assertEquals("dwd.user_profile.dt", columns.get(1).getTargetColumn().getQualifiedName(), "static partition target");
        TestSupport.assertEquals("constant('20260701')", columns.get(1).getExpression(), "static partition expression");
        TestSupport.assertTrue(columns.get(1).getSourceColumns().isEmpty(), "static partition source columns");
        TestSupport.assertEquals(LineageType.CONSTANT, columns.get(1).getLineageType(), "static partition lineage type");
    }

    @Test
    public void mixedStaticAndDynamicPartitionColumnsAreAligned() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.user_tag_detail", "user_id", "tag", "source_system", "dt")
            .addTable("ods.user_base", "user_id", "tags", "dt");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_partition")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.user_tag_detail partition(source_system='profile', dt) "
                    + "select user_id, tag, dt "
                    + "from ods.user_base lateral view explode(tags) exploded_tags as tag;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals("dwd.user_tag_detail.dt", columns.get(2).getTargetColumn().getQualifiedName(), "dynamic partition target");
        TestSupport.assertEquals("ods.user_base.dt", columns.get(2).getSourceColumns().get(0).getQualifiedName(), "dynamic partition source");
        TestSupport.assertEquals("dwd.user_tag_detail.source_system", columns.get(3).getTargetColumn().getQualifiedName(), "static partition target");
        TestSupport.assertEquals(LineageType.CONSTANT, columns.get(3).getLineageType(), "static partition type");
        TestSupport.assertEquals("constant('profile')", columns.get(3).getExpression(), "static partition expression");
    }

    @Test
    public void unionDynamicPartitionUsesPartitionColumnWhenTargetMetadataOmitsIt() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.union_partition_target", "id", "name")
            .addTable("ods.union_source_a", "id", "name", "dt")
            .addTable("ods.union_source_b", "id", "name", "dt");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_union_dynamic_partition")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.union_partition_target partition(dt) "
                    + "select id, name, date_sub(dt, 1) as dt from ods.union_source_a "
                    + "union all "
                    + "select id, name, date_sub(dt, 1) as dt from ods.union_source_b;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(
            "dwd.union_partition_target.id",
            columns.get(0).getTargetColumn().getQualifiedName(),
            "union dynamic partition id target"
        );
        TestSupport.assertEquals(
            "dwd.union_partition_target.name",
            columns.get(1).getTargetColumn().getQualifiedName(),
            "union dynamic partition name target"
        );
        TestSupport.assertEquals(
            "dwd.union_partition_target.dt",
            columns.get(2).getTargetColumn().getQualifiedName(),
            "union dynamic partition target"
        );
        assertContainsColumn(columns.get(2).getSourceColumns(), "ods.union_source_a.dt");
        assertContainsColumn(columns.get(2).getSourceColumns(), "ods.union_source_b.dt");
        TestSupport.assertEquals(LineageType.DERIVED, columns.get(2).getLineageType(), "union dynamic partition type");
    }

    @Test
    public void selectStarExpandsColumnsFromMetadata() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name")
            .addTable("dwd.user_profile", "user_id", "user_name");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_024")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile select * from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(2, columns.size(), "select star column count");
        TestSupport.assertEquals("dwd.user_profile.user_id", columns.get(0).getTargetColumn().getQualifiedName(), "select star first target");
        TestSupport.assertEquals("ods.user_base.user_id", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "select star first source");
        TestSupport.assertEquals("dwd.user_profile.user_name", columns.get(1).getTargetColumn().getQualifiedName(), "select star second target");
        TestSupport.assertEquals("ods.user_base.user_name", columns.get(1).getSourceColumns().get(0).getQualifiedName(), "select star second source");
    }

    @Test
    public void selectStarFailsWhenSourceHasExtraColumns() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "id", "name", "user_name", "dept_id", "tags", "dt", "amount", "status")
            .addTable("dwd.user_base_snapshot", "user_id", "user_name", "dept_id", "status", "target_only", "tags", "dt");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_star_target")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_base_snapshot select * from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(2, statement.getParseStatusCode(), "source extra star parse status");
        TestSupport.assertEquals("COLUMN_COUNT_MISMATCH", statement.getFailureCode(), "source extra star failure code");
        TestSupport.assertEquals(0, statement.getColumnLineages().size(), "source extra star lineage count");
    }

    @Test
    public void qualifiedStarExpandsMatchedPhysicalTableInSelectPosition() {
        assertQualifiedStarLineage(
            "insert into whc.c select whc.a.field1, whc.b.* "
                + "from whc.a left join whc.b on whc.a.id = whc.b.id;",
            "physical qualified owner"
        );
    }

    @Test
    public void threePartProjectionColumnResolvesAsOneDirectSource() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.c", "field1", "id", "name", "value");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_three_part_projection")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert into whc.c select whc.a.field1 from whc.a;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(1, columns.size(), "three-part projection lineage count");
        TestSupport.assertEquals(LineageType.DIRECT, columns.get(0).getLineageType(), "three-part projection type");
        TestSupport.assertEquals(1, columns.get(0).getSourceColumns().size(), "three-part projection source count");
        TestSupport.assertEquals("whc.a.field1", columns.get(0).getSourceColumns().get(0).getQualifiedName(), "three-part projection source");
    }

    @Test
    public void qualifiedStarExpandsExplicitAliasInSelectPosition() {
        assertQualifiedStarLineage(
            "insert into whc.c select x.field1, y.* "
                + "from whc.a x left join whc.b y on x.id = y.id;",
            "explicit alias owner"
        );
    }

    @Test
    public void qualifiedStarExpandsUniqueSimpleTableNameInSelectPosition() {
        assertQualifiedStarLineage(
            "insert into whc.c select a.field1, b.* "
                + "from whc.a left join whc.b on a.id = b.id;",
            "simple table owner"
        );
    }

    @Test
    public void unresolvedQualifiedStarDoesNotBindOwnerAsDefaultTableColumn() {
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.c", "field1");
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_unknown_qualified_star")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert into whc.c select missing.* from whc.a;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        StatementLineageResult statement = new HiveScriptLineageAnalyzer()
            .analyzeScript(request).getStatements().get(0);
        assertContainsWarning(statement.getWarnings(), WarningCode.UNKNOWN_COLUMN_LINEAGE);
        TestSupport.assertEquals(1, statement.getColumnLineages().size(), "unresolved star lineage count");
        TestSupport.assertEquals(0, statement.getColumnLineages().get(0).getSourceColumns().size(), "unresolved star sources");
    }

    @Test
    public void explicitAliasPrecedesCollidingSimpleTableNameForQualifiedStar() {
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("db1.a", "id", "field1")
            .addTable("db2.b", "other")
            .addTable("target.c", "id", "field1");
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_colliding_qualified_star")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert into target.c select b.* from db1.a b "
                    + "join db2.b y on b.id = y.other;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();
        List<ColumnLineage> columns = new HiveScriptLineageAnalyzer()
            .analyzeScript(request).getStatements().get(0).getColumnLineages();
        TestSupport.assertEquals(2, columns.size(), "colliding alias lineage count");
        assertDirectColumn(columns.get(0), "target.c.id", "db1.a.id", "colliding alias id");
        assertDirectColumn(columns.get(1), "target.c.field1", "db1.a.field1", "colliding alias field1");
    }

    @Test
    public void subqueryAliasColumnLineageResolvesToBaseTable() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("dwd.user_profile", "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_025")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.user_profile "
                    + "select x.user_id "
                    + "from (select user_id from ods.user_base) x;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals(
            "ods.user_base.user_id",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "subquery source column"
        );
    }

    @Test
    public void inlineSubqueryAliasScopeDoesNotLeakOuterAlias() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.main_account", "id")
            .addTable("ods.outer_conflict", "id", "vrf_rst_cd")
            .addTable("ods.inner_base", "id")
            .addTable("ods.inner_vrf", "cust_id", "vrf_rst_cd")
            .addTable("dwd.alias_scope_target", "vrf_rst_cd");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_subquery_alias_scope")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.alias_scope_target(vrf_rst_cd) "
                    + "select t5.vrf_rst_cd "
                    + "from ods.main_account t1 "
                    + "left join ods.outer_conflict t2 on t1.id = t2.id "
                    + "left join ("
                    + "  select t2.cust_id, t2.vrf_rst_cd "
                    + "  from ods.inner_base t1 join ods.inner_vrf t2 on t1.id = t2.cust_id"
                    + ") t5 on t1.id = t5.cust_id;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);
        ColumnLineage vrfResult = statement.getColumnLineages().get(0);

        assertContainsColumn(vrfResult.getSourceColumns(), "ods.inner_vrf.vrf_rst_cd");
        TestSupport.assertTrue(
            !containsColumn(vrfResult.getSourceColumns(), "ods.outer_conflict.vrf_rst_cd"),
            "outer alias t2 should not leak into inline subquery"
        );
    }

    private void assertQualifiedStarLineage(String sql, String messagePrefix) {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.b", "id", "name", "value")
            .addTable("whc.c", "field1", "id", "name", "value");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_qualified_star")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(sql)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        TestSupport.assertEquals(4, columns.size(), messagePrefix + " lineage count");
        assertDirectColumn(columns.get(0), "whc.c.field1", "whc.a.field1", messagePrefix + " field1");
        assertDirectColumn(columns.get(1), "whc.c.id", "whc.b.id", messagePrefix + " id");
        assertDirectColumn(columns.get(2), "whc.c.name", "whc.b.name", messagePrefix + " name");
        assertDirectColumn(columns.get(3), "whc.c.value", "whc.b.value", messagePrefix + " value");
    }

    private void assertDirectColumn(ColumnLineage lineage, String target, String source, String messagePrefix) {
        TestSupport.assertEquals(target, lineage.getTargetColumn().getQualifiedName(), messagePrefix + " target");
        TestSupport.assertEquals(LineageType.DIRECT, lineage.getLineageType(), messagePrefix + " type");
        TestSupport.assertEquals(1, lineage.getSourceColumns().size(), messagePrefix + " source count");
        TestSupport.assertEquals(source, lineage.getSourceColumns().get(0).getQualifiedName(), messagePrefix + " source");
    }
}
