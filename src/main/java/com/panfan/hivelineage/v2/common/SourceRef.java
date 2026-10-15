package com.panfan.hivelineage.v2.common;

import java.util.Objects;

/**
 * Optional rendering provenance; no SQL text or executable resolver is stored.
 */
public final class SourceRef {
    private final String sourceId;
    private final SourceSpan span;

    public SourceRef(String sourceId, SourceSpan span) {
        this.sourceId = ModelChecks.text(sourceId, "sourceId");
        this.span = ModelChecks.value(span, "span");
        if (!sourceId.equals(span.getSourceId())) {
            throw new IllegalArgumentException("span must identify the same source");
        }
    }

    public static SourceRef synthetic(String sourceId) {
        return new SourceRef(sourceId, new SourceSpan(sourceId, 0, 0));
    }

    public String getSourceId() {
        return sourceId;
    }

    public SourceSpan getSpan() {
        return span;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SourceRef)) {
            return false;
        }
        SourceRef that = (SourceRef) other;
        return sourceId.equals(that.sourceId) && span.equals(that.span);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, span);
    }
}
