package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;

public final class ExpressionFacts {
    private final NodeId expressionId;
    private final List<Dependency> dependencies;
    private final AnalysisQuality quality;
    private final DiagnosticBag diagnostics;

    public ExpressionFacts(NodeId expressionId, List<Dependency> dependencies, AnalysisQuality quality, DiagnosticBag diagnostics) {
        this.expressionId = ModelChecks.value(expressionId, "expressionId");
        this.dependencies = ModelCollections.immutableList(dependencies);
        this.quality = ModelChecks.value(quality, "quality");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public NodeId getExpressionId() {
        return expressionId;
    }

    public List<Dependency> getDependencies() {
        return dependencies;
    }

    public AnalysisQuality getQuality() {
        return quality;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
