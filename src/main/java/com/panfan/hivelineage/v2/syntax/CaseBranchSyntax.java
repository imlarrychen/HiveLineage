package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class CaseBranchSyntax {
    private final SyntaxExpression whenExpression;
    private final SyntaxExpression thenExpression;

    public CaseBranchSyntax(SyntaxExpression whenExpression, SyntaxExpression thenExpression) {
        this.whenExpression = Objects.requireNonNull(whenExpression, "whenExpression");
        this.thenExpression = Objects.requireNonNull(thenExpression, "thenExpression");
    }

    public SyntaxExpression getWhenExpression() {
        return whenExpression;
    }

    public SyntaxExpression getThenExpression() {
        return thenExpression;
    }
}
