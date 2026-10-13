package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class TypeFieldSyntax {
    private final IdentifierPart name;
    private final DataTypeSyntax type;

    public TypeFieldSyntax(IdentifierPart name, DataTypeSyntax type) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
    }

    public IdentifierPart getName() {
        return name;
    }

    public DataTypeSyntax getType() {
        return type;
    }
}
