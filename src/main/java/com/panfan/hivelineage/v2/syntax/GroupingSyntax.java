package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class GroupingSyntax {
    private final GroupingKindSyntax kind;
    private final List<List<SyntaxExpression>> sets;

    public GroupingSyntax(GroupingKindSyntax kind, List<List<SyntaxExpression>> sets) {
        this.kind = Objects.requireNonNull(kind, "kind");
        List<List<SyntaxExpression>> copied = new java.util.ArrayList<List<SyntaxExpression>>();
        for (List<SyntaxExpression> group : sets) {
            copied.add(ModelCollections.immutableList(group));
        }
        this.sets = ModelCollections.immutableList(copied);
    }

    public GroupingKindSyntax getKind() {
        return kind;
    }

    public List<List<SyntaxExpression>> getSets() {
        return sets;
    }
}
