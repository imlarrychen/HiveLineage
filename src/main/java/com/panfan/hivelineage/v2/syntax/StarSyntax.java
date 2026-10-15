package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

/**
 * A syntactic star; no output width or physical source is assumed.
 */
public final class StarSyntax extends SyntaxExpression {
    private final SyntaxName qualifier;

    public StarSyntax(NodeId nodeId, SourceRef sourceRef, SyntaxName qualifier) {
        super(nodeId, sourceRef);
        this.qualifier = qualifier;
    }

    public SyntaxName getQualifier() {
        return qualifier;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.STAR;
    }
}
