package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;
import java.util.Objects;

public final class MergeStatementSyntax extends SyntaxStatement {
    private final List<CteSyntax> ctes;
    private final MergeBodySyntax body;

    public MergeStatementSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            List<CteSyntax> ctes,
            MergeBodySyntax body) {
        super(nodeId, sourceRef);
        this.ctes = ModelCollections.immutableList(ctes);
        this.body = Objects.requireNonNull(body, "body");
    }

    public List<CteSyntax> getCtes() {
        return ctes;
    }

    public MergeBodySyntax getBody() {
        return body;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.MERGE;
    }
}
