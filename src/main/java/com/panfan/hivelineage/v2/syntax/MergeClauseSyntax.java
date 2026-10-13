package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class MergeClauseSyntax {
    private final MergeMatchKindSyntax matchKind;
    private final SyntaxExpression condition;
    private final MergeActionSyntax action;

    public MergeClauseSyntax(MergeMatchKindSyntax matchKind, SyntaxExpression condition, MergeActionSyntax action) {
        this.matchKind = Objects.requireNonNull(matchKind, "matchKind");
        this.condition = condition;
        this.action = Objects.requireNonNull(action, "action");
    }

    public MergeMatchKindSyntax getMatchKind() {
        return matchKind;
    }

    public SyntaxExpression getCondition() {
        return condition;
    }

    public MergeActionSyntax getAction() {
        return action;
    }
}
