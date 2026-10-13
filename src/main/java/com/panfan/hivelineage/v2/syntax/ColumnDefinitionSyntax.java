package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class ColumnDefinitionSyntax {
    private final IdentifierPart name;
    private final DataTypeSyntax type;
    private final String comment;

    public ColumnDefinitionSyntax(IdentifierPart name, DataTypeSyntax type, String comment) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = type;
        this.comment = comment;
    }

    public IdentifierPart getName() {
        return name;
    }

    public DataTypeSyntax getType() {
        return type;
    }

    public String getComment() {
        return comment;
    }
}
