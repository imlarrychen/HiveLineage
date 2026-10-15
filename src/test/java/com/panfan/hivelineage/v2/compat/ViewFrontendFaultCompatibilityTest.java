package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.testing.ViewFrontendFaultAnalyzerFactory;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

public class ViewFrontendFaultCompatibilityTest {
    @Test
    public void actualViewFrontendFaultKeepsTheOriginalBoundaryAndLaterFieldMapping() {
        AtomicInteger faults = new AtomicInteger();
        HiveScriptLineageAdapter analyzer = ViewFrontendFaultAnalyzerFactory.createWithViewFrontendFault(faults);
        HiveMetadataProvider provider = table -> "dim.v_broken_user".equals(table.getQualifiedName())
                ? HiveTableMetadata.view(table, Collections.singletonList("user_id"),
                "select bad_view_marker as user_id from ods.user_base")
                : HiveTableMetadata.table(table, Collections.singletonList("user_id"));
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf_001")
                .taskId("task_view_expansion_failed").taskVersion("V1").defaultDatabase("default")
                .metadataProvider(provider).options(LineageAnalyzeOptions.builder().expandViewLineage(true).build())
                .rawScript("insert overwrite table dwd.user_profile(user_id) select user_id from dim.v_broken_user;"
                        + "insert into dwd.recovery(user_id) select user_id from ods.user_base;").build();
        ScriptAnalysisResult nativeResult = analyzer.analyzeNative(request);
        LineageAnalyzeResult result = new DefaultLegacyResultProjector().project(nativeResult).getResult();
        Assert.assertEquals(1, faults.get());
        Assert.assertEquals(0, result.getFailedStatementCount());
        Assert.assertEquals(2, result.getStatements().size());
        StatementLineageResult brokenView = result.getStatements().get(0);
        Assert.assertEquals("dim.v_broken_user", brokenView.getInputObjects().get(0).getQualifiedName());
        Assert.assertTrue(brokenView.getWarnings().stream()
                .anyMatch(warning -> warning.getWarningCode() == WarningCode.VIEW_EXPANSION_FAILED));
        Assert.assertEquals("dim.v_broken_user.user_id", brokenView.getColumnLineages().get(0)
                .getSourceColumns().get(0).getQualifiedName());
        Assert.assertTrue(nativeResult.getStatements().get(0).getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> "VIEW_PREPARATION_FAILED".equals(diagnostic.getCode())
                        && "broken view definition".equals(diagnostic.getMessage())));
        StatementLineageResult recovery = result.getStatements().get(1);
        Assert.assertEquals(Integer.valueOf(0), recovery.getParseStatusCode());
        Assert.assertTrue(recovery.isFormalLineageProduced());
        Assert.assertEquals("dwd.recovery.user_id", recovery.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        Assert.assertEquals("ods.user_base.user_id", recovery.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName());
        Assert.assertTrue(recovery.getWarnings().isEmpty());
    }
}
