package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import java.util.List;
import java.util.Objects;

public final class SetQuerySyntax extends SyntaxQuery {
    private final SetOperationBodySyntax body;

    public SetQuerySyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            QueryBlockId queryBlockId,
            List<CteSyntax> ctes,
            SetOperationBodySyntax body) {
        super(nodeId, sourceRef, queryBlockId, ctes);
        this.body = Objects.requireNonNull(body, "body");
    }

    public SetOperationBodySyntax getBody() {
        return body;
    }
}
