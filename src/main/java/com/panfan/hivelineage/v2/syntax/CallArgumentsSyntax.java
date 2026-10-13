package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class CallArgumentsSyntax {
    private final List<SyntaxExpression> arguments;
    private final QuantifierSyntax quantifier;
    private final SyntaxExpression filter;

    public CallArgumentsSyntax(List<SyntaxExpression> arguments, QuantifierSyntax quantifier, SyntaxExpression filter) {
        this.arguments = ModelCollections.immutableList(arguments);
        this.quantifier = Objects.requireNonNull(quantifier, "quantifier");
        this.filter = filter;
    }

    public List<SyntaxExpression> getArguments() {
        return arguments;
    }

    public QuantifierSyntax getQuantifier() {
        return quantifier;
    }

    public SyntaxExpression getFilter() {
        return filter;
    }
}
