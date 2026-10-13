package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class FrameBoundarySyntax {
    private final FrameBoundaryKindSyntax kind;
    private final SyntaxExpression offset;

    public FrameBoundarySyntax(FrameBoundaryKindSyntax kind, SyntaxExpression offset) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.offset = offset;
    }

    public FrameBoundaryKindSyntax getKind() {
        return kind;
    }

    public SyntaxExpression getOffset() {
        return offset;
    }
}
