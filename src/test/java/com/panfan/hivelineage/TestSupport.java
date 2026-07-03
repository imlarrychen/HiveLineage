package com.panfan.hivelineage;

final class TestSupport {
    private TestSupport() {
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message + " expected true");
        }
    }
}
