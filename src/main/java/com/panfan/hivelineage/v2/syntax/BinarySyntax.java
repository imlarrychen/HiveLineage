package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class BinarySyntax extends SyntaxExpression {
    private final BinaryOperatorSyntax operator;
    private final SyntaxExpression left;
    private final SyntaxExpression right;

    public BinarySyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            BinaryOperatorSyntax operator,
            SyntaxExpression left,
            SyntaxExpression right) {
        super(nodeId, sourceRef);
        this.operator = Objects.requireNonNull(operator, "operator");
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
    }

    public BinaryOperatorSyntax getOperator() {
        return operator;
    }

    public SyntaxExpression getLeft() {
        return left;
    }

    public SyntaxExpression getRight() {
        return right;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.BINARY;
    }
}
