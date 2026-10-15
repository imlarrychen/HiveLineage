package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class BetweenSyntax extends SyntaxExpression {
    private final SyntaxExpression expression;
    private final SyntaxExpression lowerBound;
    private final SyntaxExpression upperBound;
    private final boolean negated;

    public BetweenSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxExpression expression,
            SyntaxExpression lowerBound,
            SyntaxExpression upperBound,
            boolean negated) {
        super(nodeId, sourceRef);
        this.expression = Objects.requireNonNull(expression, "expression");
        this.lowerBound = Objects.requireNonNull(lowerBound, "lowerBound");
        this.upperBound = Objects.requireNonNull(upperBound, "upperBound");
        this.negated = negated;
    }

    public SyntaxExpression getExpression() {
        return expression;
    }

    public SyntaxExpression getLowerBound() {
        return lowerBound;
    }

    public SyntaxExpression getUpperBound() {
        return upperBound;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.BETWEEN;
    }
}
