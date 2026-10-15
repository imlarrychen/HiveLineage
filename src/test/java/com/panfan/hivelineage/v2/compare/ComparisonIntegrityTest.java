package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.WarningCode;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.MetadataSnapshot;
import com.panfan.hivelineage.v2.metadata.SnapshotMetadataServices;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ComparisonIntegrityTest {
    @Test
    public void comparisonPreservesNamespaceAndAbsentDatabase() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("hivevar:x", "A");
        variables.put("hiveconf:x", "B");
        LineageAnalyzeRequest original = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("1")
                .defaultDatabase(null).runtimeVariables(variables).rawScript("select 1").build();
        ScriptAnalysisRequest request = new DefaultRequestAdapter().adapt(original);
        MetadataSnapshot snapshot = new MetadataSnapshot(request.getMetadataContext(), Collections.emptyList());
        SnapshotMetadataServices replay = new SnapshotMetadataServices(snapshot);
        WorkflowContext workflow = new WorkflowContext("wf", request.getMetadataContext(), replay, new DefaultMetadataCache());
        AtomicReference<LineageAnalyzeRequest> captured = new AtomicReference<>();
        new DefaultLineageComparisonRunner(legacyRequest -> {
            captured.set(legacyRequest);
            return LineageAnalyzeResult.builder().workflowId("wf").taskId("task").taskVersion("1").build();
        }, new DefaultScriptLineageAnalyzer()).compare(request, workflow, snapshot);
        assertEquals(variables, captured.get().getRuntimeVariables());
        assertNull(captured.get().getDefaultDatabase());
    }

    @Test
    public void compareDetectsBothJoinObjectViewsAndDiagnosticMessages() {
        StatementLineageResult first = statement("db.a", "db.physical_a", "message A", "select a", "failure A");
        StatementLineageResult second = statement("db.b", "db.physical_b", "message B", "select b", "failure B");
        LineageDifferenceReport report = LegacyStructureDiffer.compare(result(first), result(second));
        assertTrue(report.getDifferences().stream().anyMatch(difference -> difference.getDescription().startsWith("joins:")));
        assertTrue(report.getDifferences().stream().anyMatch(difference -> difference.getDescription().startsWith("warnings:")));
        assertTrue(report.getDifferences().stream().anyMatch(difference -> difference.getDescription().startsWith("rawSql:")));
        assertTrue(report.getDifferences().stream().anyMatch(difference -> difference.getDescription().startsWith("failureMessage:")));
    }

    @Test
    public void countColumnObjectSourcesAndItsOwnStatementIndexAreCompared() {
        ColumnLineage first = ColumnLineage.builder().targetColumn(new ColumnRef("db.out.count"))
                .sourceObjects(Collections.singletonList(new DataObjectRef("db.a"))).expression("count(*)").statementIndex(0).build();
        ColumnLineage second = ColumnLineage.builder().targetColumn(new ColumnRef("db.out.count"))
                .sourceObjects(Collections.singletonList(new DataObjectRef("db.b"))).expression("count(*)").statementIndex(7).build();
        StatementLineageResult left = StatementLineageResult.builder().workflowId("wf").taskId("task").taskVersion("1")
                .statementId("wf_task_1_0").columnLineages(Collections.singletonList(first)).build();
        StatementLineageResult right = StatementLineageResult.builder().workflowId("wf").taskId("task").taskVersion("1")
                .statementId("wf_task_1_0").columnLineages(Collections.singletonList(second)).build();
        assertTrue(LegacyStructureDiffer.compare(result(left), result(right)).getDifferences().stream()
                .anyMatch(difference -> difference.getDescription().startsWith("columns:")));
    }

    private static StatementLineageResult statement(String direct, String physical, String message, String sql, String failure) {
        JoinCondition join = JoinCondition.builder().joinIndex(1).queryBlockPath("q0").joinTypeName("INNER_JOIN")
                .directJoinObjects(Collections.singletonList(new DataObjectRef(direct)))
                .joinTables(Collections.singletonList(new DataObjectRef(physical))).build();
        return StatementLineageResult.builder().workflowId("wf").taskId("task").taskVersion("1").statementIndex(1)
                .statementId("wf_task_1_1").parseStatusCode(0).rawSql(sql).failureMessage(failure)
                .joinConditions(Collections.singletonList(join)).warnings(Collections.singletonList(new LineageWarning(WarningCode.METADATA_NOT_FOUND, message, 1))).build();
    }

    private static LineageAnalyzeResult result(StatementLineageResult statement) {
        return LineageAnalyzeResult.builder().workflowId("wf").taskId("task").taskVersion("1")
                .statements(Collections.singletonList(statement)).totalStatementCount(1).build();
    }
}
