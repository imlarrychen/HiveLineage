package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class JoinOperandsSyntax {
    private final FromSyntax left;
    private final FromSyntax right;

    public JoinOperandsSyntax(FromSyntax left, FromSyntax right) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
    }

    public FromSyntax getLeft() {
        return left;
    }

    public FromSyntax getRight() {
        return right;
    }
}
