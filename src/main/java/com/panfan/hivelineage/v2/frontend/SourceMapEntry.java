package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.SourceSpan;
import java.util.Objects;

/** A half-open range association, including replacements whose widths differ. */
public final class SourceMapEntry {
    private final SourceSpan originalSpan;
    private final SourceSpan expandedSpan;
    private final SourceMappingKind kind;

    public SourceMapEntry(SourceSpan originalSpan, SourceSpan expandedSpan, SourceMappingKind kind) {
        this.originalSpan = Objects.requireNonNull(originalSpan, "originalSpan");
        this.expandedSpan = Objects.requireNonNull(expandedSpan, "expandedSpan");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public SourceSpan getOriginalSpan() {
        return originalSpan;
    }

    public SourceSpan getExpandedSpan() {
        return expandedSpan;
    }

    public SourceMappingKind getKind() {
        return kind;
    }
}
