package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;

import java.util.List;
import java.util.Objects;

public abstract class SyntaxQuery extends SyntaxNode {
    private final QueryBlockId queryBlockId;
    private final List<CteSyntax> ctes;

    protected SyntaxQuery(
            NodeId nodeId,
            SourceRef sourceRef,
            QueryBlockId queryBlockId,
            List<CteSyntax> ctes) {
        super(nodeId, sourceRef);
        this.queryBlockId = Objects.requireNonNull(queryBlockId, "queryBlockId");
        this.ctes = ModelCollections.immutableList(ctes);
    }

    public QueryBlockId getQueryBlockId() {
        return queryBlockId;
    }

    public List<CteSyntax> getCtes() {
        return ctes;
    }
}
