package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

public final class LiteralSyntax extends SyntaxExpression {
    private final LiteralKind literalKind;
    private final String lexeme;
    private final String decodedValue;

    public LiteralSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            LiteralKind literalKind,
            String lexeme,
            String decodedValue) {
        super(nodeId, sourceRef);
        this.literalKind = Objects.requireNonNull(literalKind, "literalKind");
        this.lexeme = Objects.requireNonNull(lexeme, "lexeme");
        this.decodedValue = decodedValue;
    }

    public LiteralKind getLiteralKind() {
        return literalKind;
    }

    public String getLexeme() {
        return lexeme;
    }

    public String getDecodedValue() {
        return decodedValue;
    }

    @Override
    public ExpressionKind getKind() {
        return ExpressionKind.LITERAL;
    }
}
