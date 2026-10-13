package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class LineageFacts {
    private final List<RelationFacts> relations;
    private final List<Dependency> writeDependencies;
    private final AnalysisQuality quality;
    private final DiagnosticBag diagnostics;
    private final EvidenceGraph evidenceGraph;

    public LineageFacts(List<RelationFacts> relations, List<Dependency> writeDependencies, AnalysisQuality quality, DiagnosticBag diagnostics) {
        this(relations, writeDependencies, quality, diagnostics, EvidenceGraph.empty());
    }

    public LineageFacts(List<RelationFacts> relations, List<Dependency> writeDependencies, AnalysisQuality quality, DiagnosticBag diagnostics, EvidenceGraph evidenceGraph) {
        this.relations = ModelCollections.immutableList(relations);
        this.writeDependencies = ModelCollections.immutableList(writeDependencies);
        this.quality = ModelChecks.value(quality, "quality");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        this.evidenceGraph = ModelChecks.value(evidenceGraph, "evidenceGraph");
    }

    public List<RelationFacts> getRelations() {
        return relations;
    }

    public List<Dependency> getWriteDependencies() {
        return writeDependencies;
    }

    public AnalysisQuality getQuality() {
        return quality;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    public EvidenceGraph getEvidenceGraph() {
        return evidenceGraph;
    }
}
