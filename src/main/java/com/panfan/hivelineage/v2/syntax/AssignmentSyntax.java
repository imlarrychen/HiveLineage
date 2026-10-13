package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class AssignmentSyntax {
    private final SyntaxName target;
    private final SyntaxExpression value;

    public AssignmentSyntax(SyntaxName target, SyntaxExpression value) {
        this.target = Objects.requireNonNull(target, "target");
        this.value = Objects.requireNonNull(value, "value");
    }

    public SyntaxName getTarget() {
        return target;
    }

    public SyntaxExpression getValue() {
        return value;
    }
}
