package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

/** Stable syntactic identity and source location; neither is a field identity. */
public abstract class SyntaxNode {
    private final NodeId nodeId;
    private final SourceRef sourceRef;

    protected SyntaxNode(NodeId nodeId, SourceRef sourceRef) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }
}
