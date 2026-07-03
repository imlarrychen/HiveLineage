package com.panfan.hivelineage.result;

public enum StatementParseStatus {
    SUCCESS(0),
    PARTIAL_SUCCESS(1),
    FAILED(2),
    IGNORED(3);

    private final int code;

    StatementParseStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
