package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class SetLocationActionSyntax extends AlterActionSyntax {
    private final SyntaxExpression location;

    public SetLocationActionSyntax(SyntaxExpression location) {
        this.location = Objects.requireNonNull(location, "location");
    }

    public SyntaxExpression getLocation() {
        return location;
    }
}
