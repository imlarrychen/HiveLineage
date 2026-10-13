package com.panfan.hivelineage.v2.semantic;

final class BindingFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    BindingFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    String getCode() {
        return code;
    }
}

