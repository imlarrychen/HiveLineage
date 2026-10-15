package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;

public final class InsertStatementSyntax extends SyntaxStatement {
    private final List<CteSyntax> ctes;
    private final FromSyntax sharedFrom;
    private final List<InsertBranchSyntax> branches;

    public InsertStatementSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            List<CteSyntax> ctes,
            FromSyntax sharedFrom,
            List<InsertBranchSyntax> branches) {
        super(nodeId, sourceRef);
        this.ctes = ModelCollections.immutableList(ctes);
        this.sharedFrom = sharedFrom;
        this.branches = ModelCollections.immutableList(branches);
    }

    public List<CteSyntax> getCtes() {
        return ctes;
    }

    public FromSyntax getSharedFrom() {
        return sharedFrom;
    }

    public List<InsertBranchSyntax> getBranches() {
        return branches;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.INSERT;
    }
}
