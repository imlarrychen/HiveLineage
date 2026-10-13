package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.api.DefaultV2LineageAnalyzer;
import com.panfan.hivelineage.v2.api.V2AnalyzeRequest;
import com.panfan.hivelineage.v2.api.V2AnalyzeResult;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import java.util.Optional;

/** Explicit V2 opt-in using the existing request and result DTOs. */
public final class HiveV2LineageAnalyzer implements CompatibleLineageAnalyzer {
    private final DefaultV2LineageAnalyzer analyzer;
    private final RequestAdapter requests;
    private final LegacyResultProjector projector;
    private final DefaultMetadataCache cache = new DefaultMetadataCache();

    public HiveV2LineageAnalyzer() {
        this(new DefaultV2LineageAnalyzer(), new DefaultRequestAdapter(), new DefaultLegacyResultProjector());
    }

    public HiveV2LineageAnalyzer(DefaultV2LineageAnalyzer analyzer, RequestAdapter requests, LegacyResultProjector projector) {
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.requests = java.util.Objects.requireNonNull(requests, "requests");
        this.projector = java.util.Objects.requireNonNull(projector, "projector");
    }

    @Override
    public LineageAnalyzeResult analyzeScript(LineageAnalyzeRequest request) {
        return projector.project(analyzeNative(request)).getResult();
    }

    public V2AnalyzeResult analyzeNative(LineageAnalyzeRequest legacy) {
        V2AnalyzeRequest request = requests.adapt(legacy);
        MetadataProvider provider = new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(
            Optional.ofNullable(legacy.getMetadataProvider()), Optional.ofNullable(legacy.getMetadataCache()), request.getMetadataContext(), false));
        WorkflowContext workflow = new WorkflowContext(request.getTask().getWorkflowId(), request.getMetadataContext(), provider, cache);
        return analyzer.analyzeScript(request, workflow);
    }
}
