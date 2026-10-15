package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.Objects;

/**
 * Preserves each name part and quoting without resolving it to an object.
 */
public final class IdentifierPart {
    private final String spelling;
    private final String value;
    private final QuoteKind quoteKind;
    private final SourceRef sourceRef;

    public IdentifierPart(
            String spelling,
            String value,
            QuoteKind quoteKind,
            SourceRef sourceRef) {
        this.spelling = Objects.requireNonNull(spelling, "spelling");
        this.value = Objects.requireNonNull(value, "value");
        this.quoteKind = Objects.requireNonNull(quoteKind, "quoteKind");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
    }

    public String getSpelling() {
        return spelling;
    }

    public String getValue() {
        return value;
    }

    public QuoteKind getQuoteKind() {
        return quoteKind;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }
}
