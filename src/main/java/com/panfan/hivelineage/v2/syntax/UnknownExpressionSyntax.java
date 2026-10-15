package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;
import java.util.Objects;

/**
 * A recovered opaque syntax interval; it is never a literal or an invented field reference.
 */
public final class UnknownExpressionSyntax extends SyntaxExpression {
    private final String reason;
    private final List<SyntaxExpression> knownChildren;

    public UnknownExpressionSyntax(NodeId nodeId, SourceRef sourceRef, String reason, List<SyntaxExpression> knownChildren) {
        super(nodeId, sourceRef);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.knownChildren = ModelCollections.immutableList(knownChildren);
    }

    public String getReason() {
        return reason;
    }

    public List<SyntaxExpression> getKnownChildren() {
        return knownChildren;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.UNKNOWN;
    }
}
