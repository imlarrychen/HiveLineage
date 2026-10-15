package com.panfan.hivelineage.v2.common;

import java.util.Objects;

/**
 * Constructor checks for data contracts; does not perform SQL analysis.
 */
public final class ModelChecks {
    private ModelChecks() {
    }

    public static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static <T> T value(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    public static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
