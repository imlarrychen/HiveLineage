package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;
import java.util.Objects;

public final class DeleteStatementSyntax extends SyntaxStatement {
    private final NamedFromSyntax target;
    private final SyntaxExpression where;
    private final List<CteSyntax> ctes;

    public DeleteStatementSyntax(NodeId nodeId, SourceRef sourceRef, NamedFromSyntax target,
                                 SyntaxExpression where, List<CteSyntax> ctes) {
        super(nodeId, sourceRef);
        this.target = Objects.requireNonNull(target, "target");
        this.where = where;
        this.ctes = ModelCollections.immutableList(ctes);
    }

    public NamedFromSyntax getTarget() {
        return target;
    }

    public SyntaxExpression getWhere() {
        return where;
    }

    public List<CteSyntax> getCtes() {
        return ctes;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.DELETE;
    }
}
