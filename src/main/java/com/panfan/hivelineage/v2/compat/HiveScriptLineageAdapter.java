package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;

import java.util.Optional;

/**
 * Drop-in V2 implementation of {@link com.panfan.hivelineage.core.HiveLineageAnalyzer}
 * using the existing request, result, options and metadata types.
 * Replace {@code new HiveScriptLineageAnalyzer()} with {@code new HiveScriptLineageAdapter()}
 * to select V2; the request's taskVersion remains the business task version.
 */
public final class HiveScriptLineageAdapter implements CompatibleLineageAnalyzer {
    private final DefaultScriptLineageAnalyzer analyzer;
    private final RequestAdapter requests;
    private final LegacyResultProjector projector;

    public HiveScriptLineageAdapter() {
        this(new DefaultScriptLineageAnalyzer(), new DefaultRequestAdapter(), new DefaultLegacyResultProjector());
    }

    public HiveScriptLineageAdapter(DefaultScriptLineageAnalyzer analyzer, RequestAdapter requests, LegacyResultProjector projector) {
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.projector = java.util.Objects.requireNonNull(projector, "projector");
    }

    @Override
    public LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request) {
        return projector.project(analyzeNative(request)).getResult();
    }

    public ScriptAnalysisResult analyzeNative(LineageAnalyzeRequest legacy) {
        ScriptAnalysisRequest request = requests.adapt(legacy);
        MetadataProvider provider = new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(
                Optional.ofNullable(legacy.getMetadataProvider()), Optional.ofNullable(legacy.getMetadataCache()), request.getMetadataContext(), false));
        // Pin V2 reads within this script. Cross-call reuse and expiry belong to the supplied V1 cache.
        WorkflowContext workflow = new WorkflowContext(request.getTask().getWorkflowId(), request.getMetadataContext(), provider,
                new DefaultMetadataCache());
        return analyzer.analyzeScript(request, workflow);
    }
}
