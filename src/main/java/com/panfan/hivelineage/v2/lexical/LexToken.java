package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.Objects;

public final class LexToken {
    private final int index;
    private final TokenKind kind;
    private final String lexeme;
    private final String decodedValue;
    private final TokenChannel channel;
    private final TokenLocation location;

    public LexToken(
            int index,
            TokenKind kind,
            String lexeme,
            String decodedValue,
            TokenChannel channel,
            TokenLocation location) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be non-negative");
        }
        this.index = index;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.lexeme = Objects.requireNonNull(lexeme, "lexeme");
        this.decodedValue = decodedValue;
        this.channel = Objects.requireNonNull(channel, "channel");
        this.location = Objects.requireNonNull(location, "location");
    }

    public int getIndex() {
        return index;
    }

    public TokenKind getKind() {
        return kind;
    }

    public String getLexeme() {
        return lexeme;
    }

    public String getDecodedValue() {
        return decodedValue;
    }

    public TokenChannel getChannel() {
        return channel;
    }

    public TokenLocation getLocation() {
        return location;
    }
    public SourceRef getSourceRef() {
        return location.getSourceRef();
    }

    public DialectSymbol getDialectSymbol() {
        return location.getDialectSymbol();
    }
}
