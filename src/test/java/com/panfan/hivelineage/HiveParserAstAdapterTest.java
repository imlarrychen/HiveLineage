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

public class HiveParserAstAdapterTest {
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
