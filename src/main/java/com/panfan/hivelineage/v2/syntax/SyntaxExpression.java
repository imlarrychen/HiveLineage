package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

public abstract class SyntaxExpression extends SyntaxNode {

    protected SyntaxExpression(NodeId nodeId, SourceRef sourceRef) {
        super(nodeId, sourceRef);
    }

    public abstract ExpressionKind getKind();
}
