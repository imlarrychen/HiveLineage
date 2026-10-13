package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.core.HiveLineageAnalyzer;
import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.DefaultV2LineageAnalyzer;
import com.panfan.hivelineage.v2.api.V2AnalyzeRequest;
import com.panfan.hivelineage.v2.api.V2AnalyzeResult;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.MetadataSnapshot;
import com.panfan.hivelineage.v2.metadata.SnapshotMetadataServices;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.session.VariableKey;
import com.panfan.hivelineage.v2.api.VariablePolicy;
import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.api.StatementPublicationPolicy;
import com.panfan.hivelineage.v2.api.DefinitionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Both engines read the same frozen payloads with separate caches and Sessions. */
public final class DefaultLineageComparisonRunner implements LineageComparisonRunner {
    private final HiveLineageAnalyzer legacy;
    private final DefaultV2LineageAnalyzer semantic;

    public DefaultLineageComparisonRunner() {
        this(new HiveScriptLineageAnalyzer(), new DefaultV2LineageAnalyzer());
    }

    public DefaultLineageComparisonRunner(HiveLineageAnalyzer legacy, DefaultV2LineageAnalyzer semantic) {
        this.legacy = legacy;
        this.semantic = semantic;
    }

    @Override
    public ComparisonResult compare(V2AnalyzeRequest request, WorkflowContext workflow, MetadataSnapshot snapshot) {
        if (!request.getMetadataContext().equals(snapshot.getReadContext()) || !workflow.getReadContext().equals(snapshot.getReadContext())) {
            throw new IllegalArgumentException("comparison must share one captured metadata context");
        }
        SnapshotMetadataServices replay = new SnapshotMetadataServices(snapshot);
        WorkflowContext frozen = new WorkflowContext(workflow.getWorkflowId(), snapshot.getReadContext(), replay, new DefaultMetadataCache(),
            Optional.of(replay), Optional.of(replay));
        LineageAnalyzeResult before = legacy.analyzeScript(asLegacy(request, replay));
        V2AnalyzeResult after = semantic.analyzeScript(request, frozen);
        LineageAnalyzeResult projected = new DefaultLegacyResultProjector().project(after).getResult();
        LineageDifferenceReport compared = LegacyStructureDiffer.compare(before, projected);
        List<Difference> differences = new ArrayList<>(compared.getDifferences());
        AnalysisContract contract = request.getContract();
        if (contract.getVariablePolicy() != VariablePolicy.CURRENT_API_FALLBACK
            || contract.getStarMappingPolicy() != StarMappingPolicy.CURRENT_API_TARGET_MAPPING
            || contract.getPublicationPolicy() != StatementPublicationPolicy.CURRENT_API
            || contract.getDefinitionPolicy() != DefinitionPolicy.CURRENT_API_CTE_REUSE) {
            differences.add(new Difference(request.getTask().getTaskId(), Optional.empty(), DifferenceKind.QUALITY,
                "V1 API cannot select this native policy: variables=" + contract.getVariablePolicy() + ", star="
                    + contract.getStarMappingPolicy() + ", publication=" + contract.getPublicationPolicy() + ", definitions=" + contract.getDefinitionPolicy(),
                DifferenceDisposition.PENDING_REVIEW));
        }
        boolean unsupported = after.getStatements().stream().anyMatch(statement -> statement.getDiagnostics().getDiagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.getCode().contains("UNSUPPORTED")));
        return new ComparisonResult(before, after, new LineageDifferenceReport(compared.getCorpusId(), differences, 1,
            unsupported ? 1 : 0, differences.isEmpty() ? 0 : 1));
    }

    private static LineageAnalyzeRequest asLegacy(V2AnalyzeRequest request, SnapshotMetadataServices replay) {
        AnalysisContract contract = request.getContract();
        boolean table = contract.getCapabilities().supports(AnalysisCapability.TABLE_LINEAGE);
        boolean column = contract.getCapabilities().supports(AnalysisCapability.COLUMN_LINEAGE);
        LineageParseMode mode = column ? table ? LineageParseMode.TABLE_AND_COLUMN : LineageParseMode.COLUMN_ONLY : LineageParseMode.TABLE_ONLY;
        LineageAnalyzeOptions options = LineageAnalyzeOptions.builder().parseMode(mode)
            .includeTemporaryLineage(contract.isIncludeTemporaryLineage()).includeIgnoredStatements(contract.isIncludeIgnoredStatements())
            .expandViewLineage(contract.isExpandViewLineage()).parseJoinConditions(contract.getCapabilities().supports(AnalysisCapability.JOIN_CONDITION))
            .maxViewExpandDepth(contract.getMaxViewExpandDepth()).failFast(contract.isFailFast()).fallbackVariableValue(contract.getFallbackVariableValue()).build();
        Map<String, String> variables = new LinkedHashMap<>();
        request.getInitialSession().getInitialVariables().forEach((key, value) -> value.getValue().ifPresent(text -> variables.put(variableName(key), text)));
        return LineageAnalyzeRequest.builder().workflowId(request.getTask().getWorkflowId()).taskId(request.getTask().getTaskId()).taskVersion(request.getTask().getTaskVersion())
            .clusterName(request.getInitialSession().getClusterName()).defaultDatabase(request.getInitialSession().getDefaultDatabase().map(name -> name.getDisplayName()).orElse(null))
            .rawScript(request.getSource().getText()).runtimeVariables(variables).options(options).metadataProvider(replay.asLegacyProvider()).metadataCache(new HiveMetadataCache()).build();
    }

    private static String variableName(VariableKey key) {
        switch (key.getNamespace()) {
            case HIVEVAR:
                return "hivevar:" + key.getName();
            case HIVECONF:
                return "hiveconf:" + key.getName();
            case SYSTEM:
                return "system:" + key.getName();
            case ENVIRONMENT:
                return "env:" + key.getName();
            default:
                return key.getName();
        }
    }
}
