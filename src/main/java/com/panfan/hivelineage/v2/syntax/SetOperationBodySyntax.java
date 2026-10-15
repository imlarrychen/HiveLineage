package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class SetOperationBodySyntax {
    private final SetOperatorSyntax operator;
    private final QuantifierSyntax quantifier;
    private final List<SyntaxQuery> branches;
    private final QueryTailSyntax tail;

    public SetOperationBodySyntax(
            SetOperatorSyntax operator,
            QuantifierSyntax quantifier,
            List<SyntaxQuery> branches,
            QueryTailSyntax tail) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.quantifier = Objects.requireNonNull(quantifier, "quantifier");
        this.branches = ModelCollections.immutableList(branches);
        this.tail = Objects.requireNonNull(tail, "tail");
    }

    public SetOperatorSyntax getOperator() {
        return operator;
    }

    public QuantifierSyntax getQuantifier() {
        return quantifier;
    }

    public List<SyntaxQuery> getBranches() {
        return branches;
    }

    public QueryTailSyntax getTail() {
        return tail;
    }
}
