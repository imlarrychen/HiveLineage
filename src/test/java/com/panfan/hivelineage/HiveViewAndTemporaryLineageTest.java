package com.panfan.hivelineage;

import com.panfan.hivelineage.core.FallbackHiveAstParserAdapter;
import com.panfan.hivelineage.core.HeuristicHiveAstParserAdapter;
import com.panfan.hivelineage.core.HiveAstParserAdapter;
import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveParserAstAdapter;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.core.ParsedHiveStatement;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.result.WarningCode;
import java.util.List;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.MapMetadataProvider;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsColumn;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsWarning;
import static com.panfan.hivelineage.AnalyzerTestSupport.assertNotContainsSource;

public class HiveViewAndTemporaryLineageTest {
    @Test
    public void temporaryTableLineageIsExpandedToFinalTargetByDefault() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_014")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "create temporary table tmp_user as select user_id from ods.user_base;\n"
                    + "insert overwrite table dwd.user_profile select user_id from tmp_user;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "temporary failed statement count");
        TestSupport.assertEquals(1, result.getStatements().size(), "temporary returned statement count");
        StatementLineageResult statement = result.getStatements().get(0);
        TestSupport.assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName(), "temporary final target");
        assertContainsSource(statement, "ods.user_base");
        assertNotContainsSource(statement, "seele_tmp_db_wf_001_task_014.tmp_user");
        TestSupport.assertTrue(statement.getTableLineages().get(0).isThroughTemporaryTable(), "temporary lineage marker");
    }

    @Test
    public void includeTemporaryLineageReturnsTemporaryCreateStatement() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_015")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript(
                "create temporary table tmp_user as select user_id from ods.user_base;\n"
                    + "insert overwrite table dwd.user_profile select user_id from tmp_user;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .includeTemporaryLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(2, result.getStatements().size(), "include temporary statement count");
        TestSupport.assertEquals("CREATE_TEMPORARY_TABLE_AS_SELECT", result.getStatements().get(0).getStatementType(), "temporary statement type");
        TestSupport.assertEquals("seele_tmp_db_wf_001_task_015.tmp_user", result.getStatements().get(0).getOutputObject().getQualifiedName(), "temporary table target");
        assertContainsSource(result.getStatements().get(0), "ods.user_base");
        assertContainsSource(result.getStatements().get(1), "seele_tmp_db_wf_001_task_015.tmp_user");
        assertNotContainsSource(result.getStatements().get(1), "ods.user_base");
    }

    @Test
    public void viewInputIsExpandedAndViewMetadataIsCached() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView(
                "dim.v_user_base",
                "select user_id, user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_016")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "insert overwrite table dwd.user_profile select user_id from dim.v_user_base;\n"
                    + "insert overwrite table dwd.user_profile_daily select user_name from dim.v_user_base;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_ONLY)
                .expandViewLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);

        TestSupport.assertEquals(0, result.getFailedStatementCount(), "view failed statement count");
        TestSupport.assertEquals(2, result.getStatements().size(), "view statement count");
        assertContainsSource(result.getStatements().get(0), "dim.v_user_base");
        assertContainsSource(result.getStatements().get(1), "dim.v_user_base");
        assertContainsTableLineage(result.getStatements().get(0), "dim.v_user_base", "ods.user_base");
        assertContainsTableLineage(result.getStatements().get(1), "dim.v_user_base", "ods.user_base");
        TestSupport.assertEquals(2, metadataProvider.getLoadCount(), "view and base metadata should be cached");
    }

    @Test
    public void createViewAsSelectProducesFormalLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_018")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("create view dim.v_user as select user_id, concat(first_name, last_name) as user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "view parse status");
        TestSupport.assertEquals("CREATE_VIEW_AS_SELECT", statement.getStatementType(), "view statement type");
        TestSupport.assertEquals("dim.v_user", statement.getOutputObject().getQualifiedName(), "view target");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals(
            "dim.v_user.user_id",
            statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "view first target"
        );
        TestSupport.assertEquals(LineageType.DERIVED, statement.getColumnLineages().get(1).getLineageType(), "view derived target");
        TestSupport.assertEquals("concat(first_name, last_name)", statement.getColumnLineages().get(1).getExpression(), "view expression");
    }

    @Test
    public void viewColumnLineagePreservesViewFieldBoundaryAndAddsDefinitionFields() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView(
                "dim.v_user_base",
                "select user_id, concat(first_name, last_name) as user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_019")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile(user_id, user_name) select user_id, user_name from dim.v_user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.COLUMN_ONLY)
                .expandViewLineage(true)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();

        ColumnLineage viewUserId = findColumnLineage(columns, "dim.v_user_base.user_id");
        TestSupport.assertEquals("ods.user_base.user_id", viewUserId.getSourceColumns().get(0).getQualifiedName(), "view direct source");

        ColumnLineage targetUserId = findColumnLineage(columns, "dwd.user_profile.user_id");
        TestSupport.assertEquals("dim.v_user_base.user_id", targetUserId.getSourceColumns().get(0).getQualifiedName(), "target direct view source");

        ColumnLineage viewUserName = findColumnLineage(columns, "dim.v_user_base.user_name");
        assertContainsColumn(viewUserName.getSourceColumns(), "ods.user_base.first_name");
        assertContainsColumn(viewUserName.getSourceColumns(), "ods.user_base.last_name");

        ColumnLineage targetUserName = findColumnLineage(columns, "dwd.user_profile.user_name");
        TestSupport.assertEquals("dim.v_user_base.user_name", targetUserName.getSourceColumns().get(0).getQualifiedName(), "target derived view source");
    }

    @Test
    public void viewInputKeepsViewBoundaryAndAddsViewDefinitionLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView(
                "dim.v_user_base",
                "select user_id, user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_view_boundary")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile(user_id, user_name) select user_id, user_name from dim.v_user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "dim.v_user_base");
        assertNotContainsSource(statement, "ods.user_base");
        assertContainsTableLineage(statement, "dwd.user_profile", "dim.v_user_base");
        assertContainsTableLineage(statement, "dim.v_user_base", "ods.user_base");
        TestSupport.assertEquals(
            "ods.user_base.user_id",
            findColumnLineage(statement.getColumnLineages(), "dim.v_user_base.user_id").getSourceColumns().get(0).getQualifiedName(),
            "view definition field source"
        );
        TestSupport.assertEquals(
            "dim.v_user_base.user_id",
            findColumnLineage(statement.getColumnLineages(), "dwd.user_profile.user_id").getSourceColumns().get(0).getQualifiedName(),
            "target view field source"
        );
    }

    @Test
    public void missingViewDefinitionEmitsWarningAndKeepsViewSource() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView("dim.v_user_base", null, "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_027")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile select user_id from dim.v_user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "dim.v_user_base");
        assertContainsWarning(statement.getWarnings(), WarningCode.VIEW_METADATA_NOT_FOUND);
    }

    @Test
    public void viewExpansionDepthExceededEmitsWarningAndKeepsBoundaryView() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        TestMetadataProvider metadataProvider = new TestMetadataProvider()
            .addView(
                "dim.v_user_level_1",
                "select user_id from dim.v_user_level_2",
                "user_id"
            )
            .addView(
                "dim.v_user_level_2",
                "select user_id from ods.user_base",
                "user_id"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_028")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile(user_id) select user_id from dim.v_user_level_1;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .maxViewExpandDepth(1)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "dim.v_user_level_1");
        assertContainsTableLineage(statement, "dim.v_user_level_1", "dim.v_user_level_2");
        assertContainsWarning(statement.getWarnings(), WarningCode.VIEW_EXPANSION_DEPTH_EXCEEDED);
    }

    @Test
    public void alterViewAsSelectProducesFormalLineage() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_028")
            .taskVersion("V1")
            .defaultDatabase("default")
            .rawScript("alter view dim.v_user as select user_id, upper(user_name) as user_name from ods.user_base;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "alter view parse status");
        TestSupport.assertEquals("ALTER_VIEW_AS_SELECT", statement.getStatementType(), "alter view statement type");
        TestSupport.assertEquals("dim.v_user", statement.getOutputObject().getQualifiedName(), "alter view target");
        assertContainsSource(statement, "ods.user_base");
        TestSupport.assertEquals(
            "dim.v_user.user_id",
            statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "alter view first target"
        );
        TestSupport.assertEquals(
            "ods.user_base.user_id",
            statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "alter view direct source"
        );
        assertContainsColumn(statement.getColumnLineages().get(1).getSourceColumns(), "ods.user_base.user_name");
    }

    @Test
    public void temporaryTableColumnLineageIsExpandedToFinalTarget() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name", "dept_id", "status", "dt")
            .addTable("ods.user_dept", "dept_id", "dept_name")
            .addTable("dwd.user_profile_enriched", "user_id", "user_name", "dept_name", "user_status", "process_date");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_temp_columns")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "create temporary table tmp_user_profile as "
                    + "select u.user_id, u.user_name, u.dept_id, d.dept_name, "
                    + "case when u.status = 1 then 'ACTIVE' else 'INACTIVE' end as user_status "
                    + "from ods.user_base u left join ods.user_dept d on u.dept_id = d.dept_id; "
                    + "insert overwrite table dwd.user_profile_enriched(user_id, user_name, dept_name, user_status, process_date) "
                    + "select user_id, user_name, dept_name, user_status, '20260701' as process_date from tmp_user_profile;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .includeTemporaryLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);
        StatementLineageResult temporaryStatement = result.getStatements().get(0);
        StatementLineageResult finalStatement = result.getStatements().get(1);

        TestSupport.assertEquals(
            "seele_tmp_db_wf_001_task_temp_columns.tmp_user_profile.user_id",
            temporaryStatement.getColumnLineages().get(0).getTargetColumn().getQualifiedName(),
            "temporary user_id target"
        );
        TestSupport.assertEquals(
            "seele_tmp_db_wf_001_task_temp_columns.tmp_user_profile.dept_name",
            temporaryStatement.getColumnLineages().get(3).getTargetColumn().getQualifiedName(),
            "temporary dept_name target"
        );
        ColumnLineage userStatus = finalStatement.getColumnLineages().get(3);
        TestSupport.assertEquals(
            "dwd.user_profile_enriched.user_status",
            userStatus.getTargetColumn().getQualifiedName(),
            "final user_status target"
        );
        TestSupport.assertEquals(
            "seele_tmp_db_wf_001_task_temp_columns.tmp_user_profile.user_status",
            userStatus.getSourceColumns().get(0).getQualifiedName(),
            "final user_status source"
        );
        TestSupport.assertEquals(LineageType.DIRECT, userStatus.getLineageType(), "final user_status type");
        TestSupport.assertEquals(
            "user_status",
            userStatus.getExpression(),
            "final user_status expression"
        );
        for (LineageWarning warning : temporaryStatement.getWarnings()) {
            TestSupport.assertTrue(
                warning.getMessage().indexOf("tmp_user_profile") < 0,
                "temporary table should not emit metadata warning"
            );
        }
    }

    @Test
    public void viewColumnLineageKeepsDerivedExpressionOnViewDefinitionEdge() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name")
            .addTable("dwd.active_user_profile", "user_id", "user_name")
            .addView(
                "dim.v_user_profile",
                "select user_id, upper(user_name) as user_name from ods.user_base",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_view_columns")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.active_user_profile(user_id, user_name) select user_id, user_name from dim.v_user_profile;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        List<ColumnLineage> columns = analyzer.analyzeScript(request).getStatements().get(0).getColumnLineages();
        ColumnLineage userName = findColumnLineage(columns, "dim.v_user_profile.user_name");
        ColumnLineage targetUserName = findColumnLineage(columns, "dwd.active_user_profile.user_name");

        TestSupport.assertEquals("ods.user_base.user_name", userName.getSourceColumns().get(0).getQualifiedName(), "expanded view user_name source");
        TestSupport.assertEquals(LineageType.DERIVED, userName.getLineageType(), "expanded view user_name type");
        TestSupport.assertEquals("upper(user_name)", userName.getExpression(), "expanded view user_name expression");
        TestSupport.assertEquals("dim.v_user_profile.user_name", targetUserName.getSourceColumns().get(0).getQualifiedName(), "target view user_name source");
    }

    @Test
    public void temporaryTableShadowsSameNameMetastoreView() {
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer();
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addTable("ods.user_base", "user_id", "user_name")
            .addTable("ods.union_source_a", "id", "name")
            .addTable("dwd.temp_shadow_target", "user_id", "user_name")
            .addView(
                "default.tmp_shadow_user",
                "select id as user_id, name as user_name from ods.union_source_a",
                "user_id",
                "user_name"
            );

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_temp_shadow")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript(
                "create temporary table tmp_shadow_user as select user_id, user_name from ods.user_base; "
                    + "insert overwrite table dwd.temp_shadow_target(user_id, user_name) select user_id, user_name from tmp_shadow_user;"
            )
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .includeTemporaryLineage(true)
                .build())
            .build();

        LineageAnalyzeResult result = analyzer.analyzeScript(request);
        StatementLineageResult finalStatement = result.getStatements().get(1);

        assertContainsSource(finalStatement, "seele_tmp_db_wf_001_task_temp_shadow.tmp_shadow_user");
        assertNotContainsSource(finalStatement, "ods.union_source_a");
        TestSupport.assertEquals(
            "seele_tmp_db_wf_001_task_temp_shadow.tmp_shadow_user.user_id",
            finalStatement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName(),
            "shadow first column"
        );
    }

    @Test
    public void viewExpansionFailureEmitsWarningAndKeepsViewBoundary() {
        HiveAstParserAdapter delegate = new FallbackHiveAstParserAdapter(new HiveParserAstAdapter(), new HeuristicHiveAstParserAdapter());
        HiveLineageAnalyzer analyzer = new HiveScriptLineageAnalyzer(new HiveAstParserAdapter() {
            @Override
            public ParsedHiveStatement parse(String normalizedSql) {
                if (normalizedSql.indexOf("bad_view_marker") >= 0) {
                    throw new IllegalArgumentException("broken view definition");
                }
                return delegate.parse(normalizedSql);
            }
        });
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView("dim.v_broken_user", "select bad_view_marker as user_id from ods.user_base", "user_id");

        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_001")
            .taskId("task_view_expansion_failed")
            .taskVersion("V1")
            .defaultDatabase("default")
            .metadataProvider(metadataProvider)
            .metadataCache(new HiveMetadataCache())
            .rawScript("insert overwrite table dwd.user_profile(user_id) select user_id from dim.v_broken_user;")
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .expandViewLineage(true)
                .build())
            .build();

        StatementLineageResult statement = analyzer.analyzeScript(request).getStatements().get(0);

        assertContainsSource(statement, "dim.v_broken_user");
        assertContainsWarning(statement.getWarnings(), WarningCode.VIEW_EXPANSION_FAILED);
    }

    @Test
    public void unqualifiedTableInViewUsesViewDatabase() {
        MapMetadataProvider metadataProvider = new MapMetadataProvider()
            .addView("view_db.v_user", "select id from base_t", "id")
            .addTable("view_db.base_t", "id")
            .addTable("session_db.target_t", "id");

        StatementLineageResult statement = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId("wf_view_db")
                .taskId("task_view_db")
                .taskVersion("V1")
                .defaultDatabase("session_db")
                .rawScript("insert into target_t(id) select id from view_db.v_user")
                .metadataProvider(metadataProvider)
                .metadataCache(new HiveMetadataCache())
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .expandViewLineage(true)
                    .build())
                .build()
        ).getStatements().get(0);

        assertContainsSource(statement, "view_db.v_user");
        assertContainsTableLineage(statement, "view_db.v_user", "view_db.base_t");
        assertNotContainsSource(statement, "session_db.base_t");
        assertContainsColumn(findColumnLineage(statement.getColumnLineages(), "view_db.v_user.id").getSourceColumns(), "view_db.base_t.id");
        assertContainsColumn(findColumnLineage(statement.getColumnLineages(), "session_db.target_t.id").getSourceColumns(), "view_db.v_user.id");
    }

    @Test
    public void temporaryTableIsExpandedBeforePermanentTableQualification() {
        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId("wf_temp_use")
                .taskId("task_temp_use")
                .taskVersion("V1")
                .defaultDatabase("db1")
                .rawScript(
                    "create temporary table tmp_t as select id from source_t; "
                        + "use db2; "
                        + "insert into target_t(id) select id from tmp_t;"
                )
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .build())
                .build()
        );
        StatementLineageResult statement = result.getStatements().get(0);

        TestSupport.assertEquals("db2.target_t", statement.getOutputObject().getQualifiedName(), "post-use target");
        assertContainsSource(statement, "db1.source_t");
        assertNotContainsSource(statement, "seele_tmp_db_wf_temp_use_task_temp_use.tmp_t");
        assertNotContainsSource(statement, "db2.tmp_t");
        assertContainsColumn(statement.getColumnLineages().get(0).getSourceColumns(), "db1.source_t.id");
    }

    @Test
    public void qualifiedTemporaryTableUsesWorkflowTaskTemporaryDatabase() {
        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId("wf_same_tmp")
                .taskId("task_same_tmp")
                .taskVersion("V1")
                .defaultDatabase("db1")
                .rawScript(
                    "create temporary table old_db.tmp_t as select id from source_t; "
                        + "insert into db2.target_t(id) select id from old_db.tmp_t;"
                )
                .options(LineageAnalyzeOptions.builder()
                    .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                    .includeTemporaryLineage(true)
                    .build())
                .build()
        );

        TestSupport.assertEquals(2, result.getStatements().size(), "qualified temporary returned statement count");
        StatementLineageResult temporary = result.getStatements().get(0);
        StatementLineageResult finalStatement = result.getStatements().get(1);

        TestSupport.assertEquals(
            "seele_tmp_db_wf_same_tmp_task_same_tmp.tmp_t",
            temporary.getOutputObject().getQualifiedName(),
            "qualified temporary target"
        );
        assertContainsSource(temporary, "db1.source_t");
        assertContainsSource(finalStatement, "seele_tmp_db_wf_same_tmp_task_same_tmp.tmp_t");
        assertNotContainsSource(finalStatement, "old_db.tmp_t");
        assertNotContainsSource(finalStatement, "db1.source_t");
    }

    private void assertContainsTableLineage(StatementLineageResult statement, String targetObject, String sourceObject) {
        for (TableLineage tableLineage : statement.getTableLineages()) {
            if (!targetObject.equals(tableLineage.getTargetObject().getQualifiedName())) {
                continue;
            }
            for (int i = 0; i < tableLineage.getSourceObjects().size(); i++) {
                if (sourceObject.equals(tableLineage.getSourceObjects().get(i).getQualifiedName())) {
                    return;
                }
            }
        }
        throw new AssertionError("Expected table lineage " + sourceObject + " -> " + targetObject);
    }

    private ColumnLineage findColumnLineage(List<ColumnLineage> columnLineages, String targetColumn) {
        for (ColumnLineage columnLineage : columnLineages) {
            if (targetColumn.equals(columnLineage.getTargetColumn().getQualifiedName())) {
                return columnLineage;
            }
        }
        throw new AssertionError("Expected target column lineage " + targetColumn);
    }
}
