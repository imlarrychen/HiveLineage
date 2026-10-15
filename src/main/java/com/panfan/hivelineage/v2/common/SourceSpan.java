package com.panfan.hivelineage.v2.common;

import java.util.Objects;

/**
 * Half-open UTF-16 character offsets into an explicitly identified source.
 */
public final class SourceSpan {
    private final String sourceId;
    private final int start;
    private final int end;

    public SourceSpan(String sourceId, int start, int end) {
        this.sourceId = ModelChecks.text(sourceId, "sourceId");
        this.start = ModelChecks.nonNegative(start, "start");
        this.end = ModelChecks.nonNegative(end, "end");
        if (end < start) {
            throw new IllegalArgumentException("end must not precede start");
        }
    }

    public String getSourceId() {
        return sourceId;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SourceSpan)) {
            return false;
        }
        SourceSpan that = (SourceSpan) other;
        return start == that.start && end == that.end && sourceId.equals(that.sourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, start, end);
    }
}
