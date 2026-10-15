package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class ScriptReadResult {
    private final ScriptUnit unit;
    private final ScriptCursor nextCursor;
    private final boolean endOfScript;
    private final List<Diagnostic> diagnostics;

    public ScriptReadResult(
            ScriptUnit unit,
            ScriptCursor nextCursor,
            boolean endOfScript,
            List<Diagnostic> diagnostics) {
        this.unit = unit;
        this.nextCursor = Objects.requireNonNull(nextCursor, "nextCursor");
        this.endOfScript = endOfScript;
        this.diagnostics = ModelCollections.immutableList(diagnostics);
    }

    public ScriptUnit getUnit() {
        return unit;
    }

    public ScriptCursor getNextCursor() {
        return nextCursor;
    }

    public boolean isEndOfScript() {
        return endOfScript;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
