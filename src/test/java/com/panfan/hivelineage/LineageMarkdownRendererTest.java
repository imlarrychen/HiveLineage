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
        StatementLineageResult statement = new StatementLineageResult(
            "wf_001",
            "task_001",
            "V1",
            0,
            "wf_001_task_001_V1_0",
            "INSERT_OVERWRITE",
            "insert overwrite table user_profile select user_id, user_name from ods.user_base",
            "insert overwrite table user_profile select user_id, user_name from ods.user_base",
            0,
            true,
            new DataObjectRef("user_profile"),
            Collections.singletonList(new DataObjectRef("ods.user_base")),
            LineageParseMode.TABLE_AND_COLUMN,
            "",
            "",
            "",
            Collections.singletonList(new TableLineage(
                new DataObjectRef("user_profile"),
                Collections.singletonList(new DataObjectRef("ods.user_base")),
                "INSERT_OVERWRITE",
                0,
                false
            )),
            Arrays.asList(
                new ColumnLineage(
                    new ColumnRef("user_profile.user_id"),
                    Collections.singletonList(new ColumnRef("ods.user_base.user_id")),
                    Collections.singletonList(new DataObjectRef("ods.user_base")),
                    "user_id",
                    true,
                    LineageType.DIRECT,
                    Collections.<String>emptyList(),
                    false,
                    0
                ),
                new ColumnLineage(
                    new ColumnRef("user_profile.source_system"),
                    Collections.<ColumnRef>emptyList(),
                    Collections.singletonList(new DataObjectRef("ods.user_base")),
                    "constant('profile')",
                    true,
                    LineageType.CONSTANT,
                    Collections.<String>emptyList(),
                    false,
                    0
                )
            )
        );
        LineageAnalyzeResult result = new LineageAnalyzeResult(
            "wf_001",
            "task_001",
            "V1",
            Collections.singletonList(statement),
            1,
            0,
            0
        );

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
