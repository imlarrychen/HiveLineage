package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.Objects;

public final class TokenLocation {
    private final SourceRef sourceRef;
    private final DialectSymbol dialectSymbol;

    public TokenLocation(SourceRef sourceRef, DialectSymbol dialectSymbol) {
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.dialectSymbol = Objects.requireNonNull(dialectSymbol, "dialectSymbol");
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }

    public DialectSymbol getDialectSymbol() {
        return dialectSymbol;
    }
}
