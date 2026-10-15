package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class MergeBodySyntax {
    private final MergeTargetSyntax target;
    private final FromSyntax source;
    private final SyntaxExpression condition;
    private final List<MergeClauseSyntax> clauses;

    public MergeBodySyntax(
            MergeTargetSyntax target,
            FromSyntax source,
            SyntaxExpression condition,
            List<MergeClauseSyntax> clauses) {
        this.target = Objects.requireNonNull(target, "target");
        this.source = Objects.requireNonNull(source, "source");
        this.condition = Objects.requireNonNull(condition, "condition");
        this.clauses = ModelCollections.immutableList(clauses);
    }

    public MergeTargetSyntax getTarget() {
        return target;
    }

    public FromSyntax getSource() {
        return source;
    }

    public SyntaxExpression getCondition() {
        return condition;
    }

    public List<MergeClauseSyntax> getClauses() {
        return clauses;
    }
}
