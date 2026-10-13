package com.panfan.hivelineage.v2.frontend;

import java.util.Objects;

public final class ScriptOptions {
    private final String delimiter;
    private final boolean preserveComments;
    private final int maximumUnitCount;

    public ScriptOptions(String delimiter, boolean preserveComments, int maximumUnitCount) {
        if (delimiter == null || delimiter.isEmpty()) {
            throw new IllegalArgumentException("delimiter must not be empty");
        }
        if (maximumUnitCount < 0) {
            throw new IllegalArgumentException("maximumUnitCount must be non-negative");
        }
        this.delimiter = Objects.requireNonNull(delimiter, "delimiter");
        this.preserveComments = preserveComments;
        this.maximumUnitCount = maximumUnitCount;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public boolean isPreserveComments() {
        return preserveComments;
    }

    public int getMaximumUnitCount() {
        return maximumUnitCount;
    }
}
