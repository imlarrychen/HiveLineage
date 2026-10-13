package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class ColumnPositionSyntax {
    private final ColumnPositionKindSyntax kind;
    private final IdentifierPart afterColumn;

    public ColumnPositionSyntax(ColumnPositionKindSyntax kind, IdentifierPart afterColumn) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.afterColumn = afterColumn;
    }

    public ColumnPositionKindSyntax getKind() {
        return kind;
    }

    public IdentifierPart getAfterColumn() {
        return afterColumn;
    }
}
