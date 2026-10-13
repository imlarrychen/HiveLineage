package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.SourceRef;
import java.util.Objects;

/** An immutable script boundary; no variable expansion or SQL interpretation is performed. */
public final class ScriptUnit {
    private final String sourceId;
    private final int statementIndex;
    private final String rawText;
    private final ScriptUnitKind kind;
    private final SourceRef sourceRef;

    public ScriptUnit(
            String sourceId,
            int statementIndex,
            String rawText,
            ScriptUnitKind kind,
            SourceRef sourceRef) {
        if (statementIndex < 0) {
            throw new IllegalArgumentException("statementIndex must be non-negative");
        }
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.statementIndex = statementIndex;
        this.rawText = Objects.requireNonNull(rawText, "rawText");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
    }

    public String getSourceId() {
        return sourceId;
    }

    public int getStatementIndex() {
        return statementIndex;
    }

    public String getRawText() {
        return rawText;
    }

    public ScriptUnitKind getKind() {
        return kind;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }
}
