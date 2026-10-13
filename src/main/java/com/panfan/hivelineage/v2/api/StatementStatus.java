package com.panfan.hivelineage.v2.api;

public enum StatementStatus {
    SUCCESS(0),
    PARTIAL_SUCCESS(1),
    FAILED(2),
    IGNORED(3);

    private final int code;

    StatementStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
