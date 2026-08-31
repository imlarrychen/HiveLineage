package com.panfan.hivelineage;

import com.panfan.hivelineage.output.LineageMarkdownRenderer;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class LineageMarkdownRendererTest {
    @Test
    public void renderIncludesTableFieldSummaryForMetadataPreparation() {
        StatementLineageResult statement = StatementLineageResult.builder()
            .workflowId("wf_001")
            .taskId("task_001")
            .taskVersion("V1")
            .statementIndex(0)
            .statementId("wf_001_task_001_V1_0")
            .statementType("INSERT_OVERWRITE")
            .rawSql("insert overwrite table user_profile select user_id, user_name from ods.user_base")
            .normalizedSql("insert overwrite table user_profile select user_id, user_name from ods.user_base")
            .parseStatusCode(0)
            .formalLineageProduced(true)
            .outputObject(new DataObjectRef("user_profile"))
            .inputObjects(Collections.singletonList(new DataObjectRef("ods.user_base")))
            .parseMode(LineageParseMode.TABLE_AND_COLUMN)
            .skipReason("")
            .failureCode("")
            .failureMessage("")
            .tableLineages(Collections.singletonList(new TableLineage(
                new DataObjectRef("user_profile"),
                Collections.singletonList(new DataObjectRef("ods.user_base")),
                "INSERT_OVERWRITE",
                0,
                false
            )))
            .columnLineages(Arrays.asList(
                ColumnLineage.builder()
                    .targetColumn(new ColumnRef("user_profile.user_id"))
                    .sourceColumns(Collections.singletonList(new ColumnRef("ods.user_base.user_id")))
                    .sourceObjects(Collections.singletonList(new DataObjectRef("ods.user_base")))
                    .expression("user_id")
                    .expressionExtracted(true)
                    .lineageType(LineageType.DIRECT)
                    .functionNames(Collections.<String>emptyList())
                    .complexExpression(false)
                    .statementIndex(0)
                    .build(),
                ColumnLineage.builder()
                    .targetColumn(new ColumnRef("user_profile.source_system"))
                    .sourceColumns(Collections.<ColumnRef>emptyList())
                    .sourceObjects(Collections.singletonList(new DataObjectRef("ods.user_base")))
                    .expression("constant('profile')")
                    .expressionExtracted(true)
                    .lineageType(LineageType.CONSTANT)
                    .functionNames(Collections.<String>emptyList())
                    .complexExpression(false)
                    .statementIndex(0)
                    .build()
            ))
            .build();
        LineageAnalyzeResult result = LineageAnalyzeResult.builder()
            .workflowId("wf_001")
            .taskId("task_001")
            .taskVersion("V1")
            .statements(Collections.singletonList(statement))
            .totalStatementCount(1)
            .ignoredStatementCount(0)
            .failedStatementCount(0)
            .build();

        String markdown = LineageMarkdownRenderer.render(result, "Test Report", "dwd");

        TestSupport.assertTrue(markdown.contains("## Tables And Fields"), "table field section");
        TestSupport.assertTrue(
            markdown.contains("| dwd.user_profile | OUTPUT | 0 | user_id<br>source_system |  | user_id<br>source_system |"),
            "output table fields"
        );
        TestSupport.assertTrue(
            markdown.contains("| ods.user_base | INPUT | 0 |  | user_id | user_id |"),
            "input table fields"
        );
    }
}
