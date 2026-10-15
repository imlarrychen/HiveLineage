package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.EvidenceId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;
import java.util.Optional;

public final class Evidence {
    private final EvidenceId id;
    private final NodeId operationId;
    private final String ruleId;
    private final List<EvidenceId> parentEvidence;
    private final Optional<SourceRef> sourceRef;

    public Evidence(EvidenceId id, NodeId operationId, String ruleId, List<EvidenceId> parentEvidence, Optional<SourceRef> sourceRef) {
        this.id = ModelChecks.value(id, "id");
        this.operationId = ModelChecks.value(operationId, "operationId");
        this.ruleId = ModelChecks.text(ruleId, "ruleId");
        this.parentEvidence = ModelCollections.immutableList(parentEvidence);
        this.sourceRef = ModelChecks.value(sourceRef, "sourceRef");
    }

    public EvidenceId getId() {
        return id;
    }

    public NodeId getOperationId() {
        return operationId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public List<EvidenceId> getParentEvidence() {
        return parentEvidence;
    }

    public Optional<SourceRef> getSourceRef() {
        return sourceRef;
    }
}
