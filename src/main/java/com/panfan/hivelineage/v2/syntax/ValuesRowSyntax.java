package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class ValuesRowSyntax {
    private final List<SyntaxExpression> values;

    public ValuesRowSyntax(List<SyntaxExpression> values) {
        this.values = ModelCollections.immutableList(values);
    }

    public List<SyntaxExpression> getValues() {
        return values;
    }
}
