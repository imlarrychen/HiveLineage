package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

public final class FieldAccessSyntax extends SyntaxExpression {
    private final SyntaxExpression base;
    private final IdentifierPart field;

    public FieldAccessSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxExpression base,
            IdentifierPart field) {
        super(nodeId, sourceRef);
        this.base = Objects.requireNonNull(base, "base");
        this.field = Objects.requireNonNull(field, "field");
    }

    public SyntaxExpression getBase() {
        return base;
    }

    public IdentifierPart getField() {
        return field;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.FIELD_ACCESS;
    }
}
