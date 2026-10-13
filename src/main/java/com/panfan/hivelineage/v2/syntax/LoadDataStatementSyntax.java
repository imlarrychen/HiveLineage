package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

public final class LoadDataStatementSyntax extends SyntaxStatement {
    private final LoadDataBodySyntax body;

    public LoadDataStatementSyntax(NodeId nodeId, SourceRef sourceRef, LoadDataBodySyntax body) {
        super(nodeId, sourceRef);
        this.body = Objects.requireNonNull(body, "body");
    }

    public LoadDataBodySyntax getBody() {
        return body;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.LOAD_DATA;
    }
}
