package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class SelectBodySyntax {
    private final QuantifierSyntax quantifier;
    private final List<SelectItemSyntax> selectItems;
    private final FromSyntax from;
    private final QueryClausesSyntax clauses;

    public SelectBodySyntax(
            QuantifierSyntax quantifier,
            List<SelectItemSyntax> selectItems,
            FromSyntax from,
            QueryClausesSyntax clauses) {
        this.quantifier = Objects.requireNonNull(quantifier, "quantifier");
        this.selectItems = ModelCollections.immutableList(selectItems);
        this.from = from;
        this.clauses = Objects.requireNonNull(clauses, "clauses");
    }

    public QuantifierSyntax getQuantifier() {
        return quantifier;
    }

    public List<SelectItemSyntax> getSelectItems() {
        return selectItems;
    }

    public FromSyntax getFrom() {
        return from;
    }

    public QueryClausesSyntax getClauses() {
        return clauses;
    }
}
