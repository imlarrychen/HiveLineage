package com.panfan.hivelineage.request;

public final class LineageAnalyzeOptions {
    private final LineageParseMode parseMode;
    private final boolean includeTemporaryLineage;
    private final boolean includeIgnoredStatements;
    private final boolean expandViewLineage;
    private final boolean parseJoinConditions;
    private final int maxViewExpandDepth;
    private final boolean failFast;
    private final String fallbackVariableValue;

    private LineageAnalyzeOptions(Builder builder) {
        this.parseMode = builder.parseMode;
        this.includeTemporaryLineage = builder.includeTemporaryLineage;
        this.includeIgnoredStatements = builder.includeIgnoredStatements;
        this.expandViewLineage = builder.expandViewLineage;
        this.parseJoinConditions = builder.parseJoinConditions;
        this.maxViewExpandDepth = builder.maxViewExpandDepth;
        this.failFast = builder.failFast;
        this.fallbackVariableValue = builder.fallbackVariableValue;
    }

    public static Builder builder() {
        return new Builder();
    }

    public LineageParseMode getParseMode() {
        return parseMode;
    }

    public boolean isIncludeTemporaryLineage() {
        return includeTemporaryLineage;
    }

    public boolean isIncludeIgnoredStatements() {
        return includeIgnoredStatements;
    }

    public boolean isExpandViewLineage() {
        return expandViewLineage;
    }

    public boolean isParseJoinConditions() {
        return parseJoinConditions;
    }

    public int getMaxViewExpandDepth() {
        return maxViewExpandDepth;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public String getFallbackVariableValue() {
        return fallbackVariableValue;
    }

    public static final class Builder {
        private LineageParseMode parseMode = LineageParseMode.TABLE_AND_COLUMN;
        private boolean includeTemporaryLineage;
        private boolean includeIgnoredStatements;
        private boolean expandViewLineage;
        private boolean parseJoinConditions;
        private int maxViewExpandDepth = 5;
        private boolean failFast;
        private String fallbackVariableValue = "__HIVE_LINEAGE_DEFAULT__";

        public Builder parseMode(LineageParseMode parseMode) {
            this.parseMode = parseMode == null ? LineageParseMode.TABLE_AND_COLUMN : parseMode;
            return this;
        }

        public Builder includeTemporaryLineage(boolean includeTemporaryLineage) {
            this.includeTemporaryLineage = includeTemporaryLineage;
            return this;
        }

        public Builder includeIgnoredStatements(boolean includeIgnoredStatements) {
            this.includeIgnoredStatements = includeIgnoredStatements;
            return this;
        }

        public Builder expandViewLineage(boolean expandViewLineage) {
            this.expandViewLineage = expandViewLineage;
            return this;
        }

        public Builder parseJoinConditions(boolean parseJoinConditions) {
            this.parseJoinConditions = parseJoinConditions;
            return this;
        }

        public Builder maxViewExpandDepth(int maxViewExpandDepth) {
            this.maxViewExpandDepth = maxViewExpandDepth;
            return this;
        }

        public Builder failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }

        public Builder fallbackVariableValue(String fallbackVariableValue) {
            this.fallbackVariableValue = fallbackVariableValue == null ? "__HIVE_LINEAGE_DEFAULT__" : fallbackVariableValue;
            return this;
        }

        public LineageAnalyzeOptions build() {
            return new LineageAnalyzeOptions(this);
        }
    }
}
