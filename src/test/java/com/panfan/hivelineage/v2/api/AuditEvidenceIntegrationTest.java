package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.WarningCode;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.export.LineageJsonRenderer;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.V1MetadataProviderAdapter;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuditEvidenceIntegrationTest {
    @Test
    public void resultRetainsEveryCommittedVersionAndClosedProof() {
        HiveMetadataProvider provider = table -> HiveTableMetadata.table(table, Collections.singletonList("id"));
        ScriptAnalysisRequest base = request("insert into db.dest select id from db.src; insert into db.dest select id from db.src", provider);
        AnalysisContract old = base.getContract();
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(EnumSet.of(AnalysisCapability.COLUMN_LINEAGE,
                AnalysisCapability.TABLE_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION)), old.getVariablePolicy(), old.getDefinitionPolicy(),
                old.getStarMappingPolicy(), old.getPublicationPolicy(), false, true, false, 5, false, old.getFallbackVariableValue());
        ScriptAnalysisRequest request = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(),
                base.getMetadataContext(), contract, base.getResourceLimits());
        ScriptAnalysisResult result = analyze(new DefaultScriptLineageAnalyzer(), request, provider);
        assertEquals(0, result.getCounts().getFailed());
        assertEquals(2, result.getDatasetVersions().size());
        DatasetVersionRecord first = result.getDatasetVersions().get(0);
        DatasetVersionRecord second = result.getDatasetVersions().get(1);
        assertEquals(VersionRef.State.KNOWN, first.getState().getVersion().getState());
        assertTrue(second.getState().getPredecessors().contains(first.getState().getVersion().getId().get()));
        assertEquals(first.getObjectId(), second.getObjectId());
        result.getStatements().forEach(statement -> statement.getLineage().ifPresent(facts ->
                facts.getEvidenceGraph().getEvidence().values().forEach(proof -> proof.getParentEvidence().forEach(parent ->
                        assertTrue(facts.getEvidenceGraph().getEvidence().containsKey(parent))))));
        String json = LineageJsonRenderer.render(result);
        assertTrue(json.contains("\"lineageEvidence\""));
        assertTrue(json.contains("\"datasetVersions\""));
        assertTrue(json.contains("\"readVersions\""));
        assertTrue(json.contains(first.getState().getVersion().getId().get().getValue()));
    }

    @Test
    public void externalDefinitionDiagnosticsAndFrontendEvidenceSurviveWarmCache() {
        HiveMetadataProvider provider = table -> "v".equals(table.getTableName())
                ? HiveTableMetadata.view(table, Collections.singletonList("id"), "select '${unknown}' as id from db.src")
                : HiveTableMetadata.table(table, Collections.singletonList("id"));
        ScriptAnalysisRequest request = request("insert into db.dest(id) select id from db.v", provider);
        DefaultScriptLineageAnalyzer analyzer = new DefaultScriptLineageAnalyzer();
        ScriptAnalysisResult cold = analyze(analyzer, request, provider);
        ScriptAnalysisResult warm = analyze(analyzer, request, provider);
        assertEquals(0, cold.getCounts().getFailed());
        assertTrue(cold.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "UNRESOLVED_VARIABLE".equals(diagnostic.getCode())));
        assertEquals(cold.getDiagnostics().getDiagnostics().size(), warm.getDiagnostics().getDiagnostics().size());
        assertTrue(warm.getFrontendEvidence().values().stream().anyMatch(unit ->
                unit.getVariableEvidence().stream().anyMatch(variable -> "unknown".equals(variable.getName()))));
        assertFalse(warm.getSources().isEmpty());
    }

    @Test
    public void frontendFaultDuringViewPreparationKeepsBoundaryAndRecovers() {
        SyntaxFrontend frontend = new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory());
        SyntaxFrontend faulty = (unit, dialect) -> {
            if (unit.getExpandedText().contains("bad_view_marker")) {
                throw new IllegalArgumentException("broken view definition");
            }
            return frontend.parse(unit, dialect);
        };
        HiveScriptLineageAdapter analyzer = new HiveScriptLineageAdapter(new DefaultScriptLineageAnalyzer(faulty, new DefaultSemanticBinder(),
                new DefaultMetadataAccessFactory()), new DefaultRequestAdapter(), new DefaultLegacyResultProjector());
        HiveMetadataProvider provider = table -> "v_broken_user".equals(table.getTableName())
                ? HiveTableMetadata.view(table, Collections.singletonList("user_id"), "select bad_view_marker as user_id from ods.user_base")
                : HiveTableMetadata.table(table, Collections.singletonList("user_id"));
        LineageAnalyzeResult result = analyzer.analyzeScript(LineageAnalyzeRequest.builder().workflowId("wf_001").taskId("task_view_expansion_failed")
                .taskVersion("V1").rawScript("insert overwrite table dwd.user_profile(user_id) select user_id from dim.v_broken_user;"
                        + "insert into dwd.user_profile(user_id) select user_id from ods.user_base;").metadataProvider(provider)
                .options(LineageAnalyzeOptions.builder().expandViewLineage(true).build()).build());
        assertEquals(0, result.getFailedStatementCount());
        assertEquals(2, result.getStatements().size());
        assertTrue(result.getStatements().get(0).getInputObjects().stream().anyMatch(object -> "dim.v_broken_user".equals(object.getQualifiedName())));
        assertTrue(result.getStatements().get(0).getWarnings().stream().anyMatch(warning -> warning.getWarningCode() == WarningCode.VIEW_EXPANSION_FAILED));
    }

    private static ScriptAnalysisRequest request(String sql, HiveMetadataProvider provider) {
        return new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder().workflowId("wf").taskId("audit").taskVersion("1")
                .rawScript(sql).metadataProvider(provider).options(LineageAnalyzeOptions.builder().expandViewLineage(true).build()).build());
    }

    private static ScriptAnalysisResult analyze(DefaultScriptLineageAnalyzer analyzer, ScriptAnalysisRequest request, HiveMetadataProvider provider) {
        WorkflowContext workflow = new WorkflowContext("wf", request.getMetadataContext(), new V1MetadataProviderAdapter(new LegacyMetadataBinding(
                Optional.of(provider), Optional.empty(), request.getMetadataContext(), false)), new DefaultMetadataCache());
        return analyzer.analyzeScript(request, workflow);
    }
}
