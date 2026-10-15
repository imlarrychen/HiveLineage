package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

/**
 * A null static value denotes a syntactically dynamic partition.
 */
public final class PartitionEntrySyntax {
    private final IdentifierPart column;
    private final SyntaxExpression staticValue;

    public PartitionEntrySyntax(IdentifierPart column, SyntaxExpression staticValue) {
        this.column = Objects.requireNonNull(column, "column");
        this.staticValue = staticValue;
    }

    public IdentifierPart getColumn() {
        return column;
    }

    public SyntaxExpression getStaticValue() {
        return staticValue;
    }
}
