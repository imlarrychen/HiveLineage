package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class CastSyntax extends SyntaxExpression {
    private final SyntaxExpression expression;
    private final DataTypeSyntax targetType;

    public CastSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxExpression expression,
            DataTypeSyntax targetType) {
        super(nodeId, sourceRef);
        this.expression = Objects.requireNonNull(expression, "expression");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
    }

    public SyntaxExpression getExpression() {
        return expression;
    }

    public DataTypeSyntax getTargetType() {
        return targetType;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.CAST;
    }
}
