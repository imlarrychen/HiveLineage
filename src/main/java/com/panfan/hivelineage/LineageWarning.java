package com.panfan.hivelineage;

public final class LineageWarning {
    private final WarningCode warningCode;
    private final String message;
    private final int statementIndex;

    public LineageWarning(WarningCode warningCode, String message, int statementIndex) {
        this.warningCode = warningCode;
        this.message = message;
        this.statementIndex = statementIndex;
    }

    public WarningCode getWarningCode() {
        return warningCode;
    }

    public String getMessage() {
        return message;
    }

    public int getStatementIndex() {
        return statementIndex;
    }
}
