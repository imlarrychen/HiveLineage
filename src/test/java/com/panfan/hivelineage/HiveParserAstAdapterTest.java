package com.panfan.hivelineage;

import com.panfan.hivelineage.core.FallbackHiveAstParserAdapter;
import com.panfan.hivelineage.core.HeuristicHiveAstParserAdapter;
import com.panfan.hivelineage.core.HiveAstParserAdapter;
import com.panfan.hivelineage.core.HiveParserAstAdapter;
import com.panfan.hivelineage.core.ParsedHiveStatement;
import com.panfan.hivelineage.core.StatementType;
import java.util.Arrays;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.assertContainsSource;

public class HiveParserAstAdapterTest {
    @Test
    public void hiveParserAstAdapterParsesInsertIntoDistinctTargetAndProjection() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "insert into whc.c(field1) select distinct field1 from whc.a"
        );

        TestSupport.assertTrue(statement.getOutputObject() != null, "insert into distinct output object");
        TestSupport.assertEquals("whc.c", statement.getOutputObject().getQualifiedName(), "insert into distinct target");
        TestSupport.assertEquals(Arrays.asList("field1"), statement.getTargetColumns(), "insert into distinct target columns");
        TestSupport.assertEquals(Arrays.asList("field1"), statement.getSelectExpressions(), "insert into distinct select expressions");
    }

    @Test
    public void hiveParserAstAdapterParsesOptionalTableInsertIntoTarget() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "insert into table whc.c(field1) select field1 from whc.a"
        );

        TestSupport.assertTrue(statement.getOutputObject() != null, "insert into table output object");
        TestSupport.assertEquals("whc.c", statement.getOutputObject().getQualifiedName(), "insert into table target");
        TestSupport.assertEquals(Arrays.asList("field1"), statement.getSelectExpressions(), "insert into table select expressions");
    }

    @Test
    public void hiveParserAstAdapterUsesMainInsertTargetWhenWhereSubqueryHasDestination() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "insert into target_db.target_table partition(dt) select s.id, s.dt "
                + "from source_db.source_table s "
                + "where s.id in (select max(i.id) from source_db.inner_table i)"
        );

        TestSupport.assertTrue(statement.getOutputObject() != null, "main insert output object");
        TestSupport.assertEquals(
            "target_db.target_table",
            statement.getOutputObject().getQualifiedName(),
            "main insert target"
        );
    }

    @Test
    public void hiveParserAstAdapterKeepsInsertOverwriteTarget() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "insert overwrite table whc.c select field1 from whc.a"
        );

        TestSupport.assertTrue(statement.getOutputObject() != null, "insert overwrite output object");
        TestSupport.assertEquals("whc.c", statement.getOutputObject().getQualifiedName(), "insert overwrite target");
    }

    @Test
    public void hiveParserAstAdapterParsesInsertJoinFromAst() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "insert overwrite table dwd.user_profile "
                + "select a.user_id, b.dept_name "
                + "from ods.user_base a "
                + "left join ods.user_dept b on a.dept_id = b.dept_id"
        );

        TestSupport.assertEquals(StatementType.INSERT_SELECT, statement.getStatementType(), "ast insert statement type");
        TestSupport.assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName(), "ast insert target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
        assertContainsSource(statement.getInputObjects(), "ods.user_dept");
    }

    @Test
    public void hiveParserAstAdapterParsesCreateTableAsSelectFromAst() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "create table dwd.user_tmp as select user_id from ods.user_base"
        );

        TestSupport.assertEquals(StatementType.CREATE_TABLE_AS_SELECT, statement.getStatementType(), "ast ctas statement type");
        TestSupport.assertEquals("dwd.user_tmp", statement.getOutputObject().getQualifiedName(), "ast ctas target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
    }

    @Test
    public void hiveParserAstAdapterDoesNotInferQualifiedStarAsCreateTableColumn() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "create temporary table dwd.user_tmp as select a.* from ods.user_base a"
        );

        TestSupport.assertEquals(StatementType.CREATE_TABLE_AS_SELECT, statement.getStatementType(), "ast ctas statement type");
        TestSupport.assertEquals(Arrays.asList("a.*"), statement.getSelectExpressions(), "ast qualified star select expressions");
        TestSupport.assertEquals(Arrays.asList(), statement.getTargetColumns(), "qualified star is not a target column");
    }

    @Test
    public void hiveParserAstAdapterParsesAlterViewAsSelectFromAst() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "alter view dim.v_user as select user_id, upper(user_name) as user_name from ods.user_base"
        );

        TestSupport.assertEquals(StatementType.ALTER_VIEW_AS_SELECT, statement.getStatementType(), "ast alter view statement type");
        TestSupport.assertEquals("dim.v_user", statement.getOutputObject().getQualifiedName(), "ast alter view target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
        TestSupport.assertEquals("user_id", statement.getTargetColumns().get(0), "ast alter view first target column");
        TestSupport.assertEquals("user_name", statement.getTargetColumns().get(1), "ast alter view second target column");
    }

    @Test
    public void hiveParserAstAdapterParsesCreateViewColumnListAndStarProjection() {
        HiveAstParserAdapter adapter = new HiveParserAstAdapter();

        ParsedHiveStatement statement = adapter.parse(
            "create view if not exists c_hjx002.v312_001998_01 (field1, field2, field3) "
                + "as select * from c_hjx002.v312_001998_04"
        );

        TestSupport.assertEquals(StatementType.CREATE_VIEW_AS_SELECT, statement.getStatementType(), "ast create view statement type");
        TestSupport.assertEquals("c_hjx002.v312_001998_01", statement.getOutputObject().getQualifiedName(), "ast create view target");
        assertContainsSource(statement.getInputObjects(), "c_hjx002.v312_001998_04");
        TestSupport.assertEquals(Arrays.asList("field1", "field2", "field3"), statement.getTargetColumns(),
            "ast create view target columns");
        TestSupport.assertEquals(Arrays.asList("*"), statement.getSelectExpressions(), "ast create view select expressions");
    }

    @Test
    public void fallbackParserAdapterUsesHeuristicWhenPrimaryFails() {
        HiveAstParserAdapter adapter = new FallbackHiveAstParserAdapter(
            new HiveAstParserAdapter() {
                @Override
                public ParsedHiveStatement parse(String normalizedSql) {
                    throw new IllegalArgumentException("primary failed");
                }
            },
            new HeuristicHiveAstParserAdapter()
        );

        ParsedHiveStatement statement = adapter.parse(
            "insert overwrite table dwd.user_profile "
                + "select user_id "
                + "from ods.user_base"
        );

        TestSupport.assertEquals(StatementType.INSERT_SELECT, statement.getStatementType(), "fallback statement type");
        TestSupport.assertEquals("dwd.user_profile", statement.getOutputObject().getQualifiedName(), "fallback target");
        assertContainsSource(statement.getInputObjects(), "ods.user_base");
    }
}
