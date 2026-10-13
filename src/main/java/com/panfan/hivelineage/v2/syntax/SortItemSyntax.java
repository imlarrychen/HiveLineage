package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class SortItemSyntax {
    private final SyntaxExpression expression;
    private final SortDirectionSyntax direction;
    private final NullOrderingSyntax nullOrdering;

    public SortItemSyntax(SyntaxExpression expression, SortDirectionSyntax direction, NullOrderingSyntax nullOrdering) {
        this.expression = Objects.requireNonNull(expression, "expression");
        this.direction = Objects.requireNonNull(direction, "direction");
        this.nullOrdering = Objects.requireNonNull(nullOrdering, "nullOrdering");
    }

    public SyntaxExpression getExpression() {
        return expression;
    }

    public SortDirectionSyntax getDirection() {
        return direction;
    }

    public NullOrderingSyntax getNullOrdering() {
        return nullOrdering;
    }
}
