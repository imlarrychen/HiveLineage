package com.panfan.hivelineage.v2.frontend;

public final class ScriptCursor {
    private final int offset;
    private final int statementIndex;

    public ScriptCursor(int offset, int statementIndex) {
        if (offset < 0 || statementIndex < 0) {
            throw new IllegalArgumentException("cursor positions must be non-negative");
        }
        this.offset = offset;
        this.statementIndex = statementIndex;
    }

    public int getOffset() {
        return offset;
    }

    public int getStatementIndex() {
        return statementIndex;
    }
}
