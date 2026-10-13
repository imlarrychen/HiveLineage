package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class LimitSyntax {
    private final SyntaxExpression offset;
    private final SyntaxExpression count;

    public LimitSyntax(SyntaxExpression offset, SyntaxExpression count) {
        this.offset = offset;
        this.count = Objects.requireNonNull(count, "count");
    }

    public SyntaxExpression getOffset() {
        return offset;
    }

    public SyntaxExpression getCount() {
        return count;
    }
}
