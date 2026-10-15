package com.panfan.hivelineage.v2.common;

import java.util.Objects;

/**
 * Value identity for diagnostics; display punctuation never participates in encoding.
 */
public final class DiagnosticKey {
    private final AnalysisStage stage;
    private final String code;
    private final String message;
    private final String sourceId;
    private final Integer start;
    private final Integer end;

    public DiagnosticKey(Diagnostic diagnostic) {
        stage = diagnostic.getStage();
        code = diagnostic.getCode();
        message = diagnostic.getMessage();
        SourceRef source = diagnostic.getSourceRef();
        sourceId = source == null ? null : source.getSourceId();
        start = source == null ? null : source.getSpan().getStart();
        end = source == null ? null : source.getSpan().getEnd();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DiagnosticKey)) {
            return false;
        }
        DiagnosticKey that = (DiagnosticKey) other;
        return stage == that.stage && code.equals(that.code) && message.equals(that.message)
                && Objects.equals(sourceId, that.sourceId) && Objects.equals(start, that.start) && Objects.equals(end, that.end);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stage, code, message, sourceId, start, end);
    }
}
