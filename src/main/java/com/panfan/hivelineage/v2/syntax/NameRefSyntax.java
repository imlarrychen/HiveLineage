package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class NameRefSyntax extends SyntaxExpression {
    private final SyntaxName name;

    public NameRefSyntax(NodeId nodeId, SourceRef sourceRef, SyntaxName name) {
        super(nodeId, sourceRef);
        this.name = Objects.requireNonNull(name, "name");
    }

    public SyntaxName getName() {
        return name;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.NAME_REF;
    }
}
