package com.panfan.hivelineage.v2.dialect.hive;

/** A normalization budget failure with its stable frontend diagnostic code. */
final class HiveNormalizationLimit extends RuntimeException {
    private final String code;

    HiveNormalizationLimit(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
