package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class QueryStatementSyntax extends SyntaxStatement {
    private final SyntaxQuery query;

    public QueryStatementSyntax(NodeId nodeId, SourceRef sourceRef, SyntaxQuery query) {
        super(nodeId, sourceRef);
        this.query = Objects.requireNonNull(query, "query");
    }

    public SyntaxQuery getQuery() {
        return query;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.QUERY;
    }
}
