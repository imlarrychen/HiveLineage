package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;

public final class CaseSyntax extends SyntaxExpression {
    private final SyntaxExpression operand;
    private final List<CaseBranchSyntax> branches;
    private final SyntaxExpression elseExpression;

    public CaseSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxExpression operand,
            List<CaseBranchSyntax> branches,
            SyntaxExpression elseExpression) {
        super(nodeId, sourceRef);
        this.operand = operand;
        this.branches = ModelCollections.immutableList(branches);
        this.elseExpression = elseExpression;
    }

    public SyntaxExpression getOperand() {
        return operand;
    }

    public List<CaseBranchSyntax> getBranches() {
        return branches;
    }

    public SyntaxExpression getElseExpression() {
        return elseExpression;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.CASE;
    }
}
