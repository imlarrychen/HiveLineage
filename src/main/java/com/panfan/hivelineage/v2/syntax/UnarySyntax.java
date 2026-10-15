package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class UnarySyntax extends SyntaxExpression {
    private final UnaryOperatorSyntax operator;
    private final SyntaxExpression operand;

    public UnarySyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            UnaryOperatorSyntax operator,
            SyntaxExpression operand) {
        super(nodeId, sourceRef);
        this.operator = Objects.requireNonNull(operator, "operator");
        this.operand = Objects.requireNonNull(operand, "operand");
    }

    public UnaryOperatorSyntax getOperator() {
        return operator;
    }

    public SyntaxExpression getOperand() {
        return operand;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.UNARY;
    }
}
