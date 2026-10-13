package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class SelectItemSyntax {
    private final SyntaxExpression expression;
    private final IdentifierPart alias;

    public SelectItemSyntax(SyntaxExpression expression, IdentifierPart alias) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.alias = alias;
    }

    public SyntaxExpression getExpression() {
        return expression;
    }

    public IdentifierPart getAlias() {
        return alias;
    }
}
