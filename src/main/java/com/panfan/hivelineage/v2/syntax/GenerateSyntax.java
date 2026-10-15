package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class GenerateSyntax {
    private final CallSyntax function;
    private final IdentifierPart tableAlias;
    private final List<IdentifierPart> columnAliases;
    private final boolean outer;

    public GenerateSyntax(
            CallSyntax function,
            IdentifierPart tableAlias,
            List<IdentifierPart> columnAliases,
            boolean outer) {
        this.function = Objects.requireNonNull(function, "function");
        this.tableAlias = Objects.requireNonNull(tableAlias, "tableAlias");
        this.columnAliases = ModelCollections.immutableList(columnAliases);
        this.outer = outer;
    }

    public CallSyntax getFunction() {
        return function;
    }

    public IdentifierPart getTableAlias() {
        return tableAlias;
    }

    public List<IdentifierPart> getColumnAliases() {
        return columnAliases;
    }

    public boolean isOuter() {
        return outer;
    }
}
