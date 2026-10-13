package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

public final class SubqueryExpressionSyntax extends SyntaxExpression {
    private final SubqueryKindSyntax subqueryKind;
    private final SyntaxQuery query;
    private final SyntaxExpression probe;

    public SubqueryExpressionSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SubqueryKindSyntax subqueryKind,
            SyntaxQuery query,
            SyntaxExpression probe) {
        super(nodeId, sourceRef);
        this.subqueryKind = Objects.requireNonNull(subqueryKind, "subqueryKind");
        this.query = Objects.requireNonNull(query, "query");
        this.probe = probe;
    }

    public SubqueryKindSyntax getSubqueryKind() {
        return subqueryKind;
    }

    public SyntaxQuery getQuery() {
        return query;
    }

    public SyntaxExpression getProbe() {
        return probe;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.SUBQUERY;
    }
}
