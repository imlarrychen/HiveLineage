package com.panfan.hivelineage.v2.lexical;

import java.util.Objects;

/**
 * Symbol identity without Hive or ANTLR integer token codes.
 */
public final class DialectSymbol {
    private final String dialectId;
    private final String symbolName;

    public DialectSymbol(String dialectId, String symbolName) {
        this.dialectId = Objects.requireNonNull(dialectId, "dialectId");
        this.symbolName = Objects.requireNonNull(symbolName, "symbolName");
    }

    public String getDialectId() {
        return dialectId;
    }

    public String getSymbolName() {
        return symbolName;
    }
}
