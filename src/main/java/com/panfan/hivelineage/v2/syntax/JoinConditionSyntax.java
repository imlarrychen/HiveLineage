package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class JoinConditionSyntax {
    private final JoinConditionKindSyntax kind;
    private final SyntaxExpression on;
    private final List<IdentifierPart> usingColumns;

    public JoinConditionSyntax(JoinConditionKindSyntax kind, SyntaxExpression on, List<IdentifierPart> usingColumns) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.on = on;
        this.usingColumns = ModelCollections.immutableList(usingColumns);
    }

    public JoinConditionKindSyntax getKind() {
        return kind;
    }

    public SyntaxExpression getOn() {
        return on;
    }

    public List<IdentifierPart> getUsingColumns() {
        return usingColumns;
    }
}
