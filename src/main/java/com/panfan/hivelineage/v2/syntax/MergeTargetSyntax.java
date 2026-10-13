package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class MergeTargetSyntax {
    private final SyntaxName name;
    private final IdentifierPart alias;

    public MergeTargetSyntax(SyntaxName name, IdentifierPart alias) {
        this.name = Objects.requireNonNull(name, "name");
        this.alias = alias;
    }

    public SyntaxName getName() {
        return name;
    }

    public IdentifierPart getAlias() {
        return alias;
    }
}
