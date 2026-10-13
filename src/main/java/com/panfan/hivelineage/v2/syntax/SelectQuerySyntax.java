package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import java.util.List;
import java.util.Objects;

public final class SelectQuerySyntax extends SyntaxQuery {
    private final SelectBodySyntax body;

    public SelectQuerySyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            QueryBlockId queryBlockId,
            List<CteSyntax> ctes,
            SelectBodySyntax body) {
        super(nodeId, sourceRef, queryBlockId, ctes);
        this.body = Objects.requireNonNull(body, "body");
    }

    public SelectBodySyntax getBody() {
        return body;
    }
}
