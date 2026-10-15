package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;
import java.util.Objects;

public final class InListSyntax extends SyntaxExpression {
    private final SyntaxExpression expression;
    private final List<SyntaxExpression> values;
    private final boolean negated;

    public InListSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxExpression expression,
            List<SyntaxExpression> values,
            boolean negated) {
        super(nodeId, sourceRef);
        this.expression = Objects.requireNonNull(expression, "expression");
        this.values = ModelCollections.immutableList(values);
        this.negated = negated;
    }

    public SyntaxExpression getExpression() {
        return expression;
    }

    public List<SyntaxExpression> getValues() {
        return values;
    }

    public boolean isNegated() {
        return negated;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.IN_LIST;
    }
}
