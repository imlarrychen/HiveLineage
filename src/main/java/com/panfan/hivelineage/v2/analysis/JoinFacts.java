package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class JoinFacts {
    private final List<JoinFact> joins;
    private final DiagnosticBag diagnostics;
    private final EvidenceGraph evidenceGraph;

    public JoinFacts(List<JoinFact> joins, DiagnosticBag diagnostics) {
        this(joins, diagnostics, EvidenceGraph.empty());
    }

    public JoinFacts(List<JoinFact> joins, DiagnosticBag diagnostics, EvidenceGraph evidenceGraph) {
        this.joins = ModelCollections.immutableList(joins);
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        this.evidenceGraph = ModelChecks.value(evidenceGraph, "evidenceGraph");
    }

    public List<JoinFact> getJoins() {
        return joins;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    public EvidenceGraph getEvidenceGraph() {
        return evidenceGraph;
    }
}
