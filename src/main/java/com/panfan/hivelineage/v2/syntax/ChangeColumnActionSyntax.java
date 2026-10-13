package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class ChangeColumnActionSyntax extends AlterActionSyntax {
    private final IdentifierPart originalName;
    private final ColumnDefinitionSyntax newDefinition;
    private final ColumnPositionSyntax position;

    public ChangeColumnActionSyntax(IdentifierPart originalName, ColumnDefinitionSyntax newDefinition, ColumnPositionSyntax position) {
        super();
        this.originalName = Objects.requireNonNull(originalName, "originalName");
        this.newDefinition = Objects.requireNonNull(newDefinition, "newDefinition");
        this.position = Objects.requireNonNull(position, "position");
    }

    public IdentifierPart getOriginalName() {
        return originalName;
    }

    public ColumnDefinitionSyntax getNewDefinition() {
        return newDefinition;
    }

    public ColumnPositionSyntax getPosition() {
        return position;
    }
}
