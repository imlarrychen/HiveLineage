package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

public final class InsertBranchSyntax {
    private final NodeId nodeId;
    private final SourceRef sourceRef;
    private final InsertModeSyntax mode;
    private final InsertTargetSyntax target;
    private final InsertSourceSyntax source;

    public InsertBranchSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            InsertModeSyntax mode,
            InsertTargetSyntax target,
            InsertSourceSyntax source) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.target = Objects.requireNonNull(target, "target");
        this.source = Objects.requireNonNull(source, "source");
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }

    public InsertModeSyntax getMode() {
        return mode;
    }

    public InsertTargetSyntax getTarget() {
        return target;
    }

    public InsertSourceSyntax getSource() {
        return source;
    }
}
