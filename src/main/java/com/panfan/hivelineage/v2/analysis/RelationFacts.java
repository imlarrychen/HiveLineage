package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.List;
import java.util.Map;

public final class RelationFacts {
    private final NodeId relationId;
    private final Map<SlotId, ExpressionFacts> outputFacts;
    private final List<Dependency> rowSetFacts;
    private final DiagnosticBag diagnostics;

    public RelationFacts(NodeId relationId, Map<SlotId, ExpressionFacts> outputFacts, List<Dependency> rowSetFacts, DiagnosticBag diagnostics) {
        this.relationId = ModelChecks.value(relationId, "relationId");
        this.outputFacts = ModelCollections.immutableMap(outputFacts);
        this.rowSetFacts = ModelCollections.immutableList(rowSetFacts);
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public NodeId getRelationId() {
        return relationId;
    }

    public Map<SlotId, ExpressionFacts> getOutputFacts() {
        return outputFacts;
    }

    public List<Dependency> getRowSetFacts() {
        return rowSetFacts;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
