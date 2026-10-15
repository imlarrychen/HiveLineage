package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class TableSampleSyntax {
    private final SampleKindSyntax kind;
    private final List<SyntaxExpression> arguments;

    public TableSampleSyntax(SampleKindSyntax kind, List<SyntaxExpression> arguments) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.arguments = ModelCollections.immutableList(arguments);
    }

    public SampleKindSyntax getKind() {
        return kind;
    }

    public List<SyntaxExpression> getArguments() {
        return arguments;
    }
}
