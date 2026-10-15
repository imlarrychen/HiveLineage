package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.dialect.DialectProfile;
import java.util.Objects;

public final class SyntaxOptions {
    public static final int DEFAULT_MAXIMUM_NESTING_DEPTH = 512;
    public static final int DEFAULT_MAXIMUM_TOKEN_COUNT = 2000000;
    public static final int DEFAULT_MAXIMUM_AST_NODE_COUNT = 2000000;
    public static final int DEFAULT_MAXIMUM_AST_DEPTH = 512;
    private final DialectProfile dialectProfile;
    private final boolean recoveryEnabled;
    private final int maximumNestingDepth;
    private final int maximumTokenCount;
    private final int maximumAstNodeCount;
    private final int maximumAstDepth;

    public SyntaxOptions(DialectProfile dialectProfile, boolean recoveryEnabled) {
        this(dialectProfile, recoveryEnabled, DEFAULT_MAXIMUM_NESTING_DEPTH, DEFAULT_MAXIMUM_TOKEN_COUNT);
    }

    public SyntaxOptions(DialectProfile dialectProfile, boolean recoveryEnabled, int maximumNestingDepth, int maximumTokenCount) {
        this(dialectProfile, recoveryEnabled, maximumNestingDepth, maximumTokenCount,
                DEFAULT_MAXIMUM_AST_NODE_COUNT, DEFAULT_MAXIMUM_AST_DEPTH);
    }

    public SyntaxOptions(DialectProfile dialectProfile, boolean recoveryEnabled, int maximumNestingDepth, int maximumTokenCount,
                         int maximumAstNodeCount, int maximumAstDepth) {
        if (maximumNestingDepth < 0 || maximumTokenCount < 0 || maximumAstNodeCount < 0 || maximumAstDepth < 0) {
            throw new IllegalArgumentException("parser resource limits must be non-negative");
        }
        this.maximumNestingDepth = maximumNestingDepth;
        this.maximumTokenCount = maximumTokenCount;
        this.maximumAstNodeCount = maximumAstNodeCount;
        this.maximumAstDepth = maximumAstDepth;
        this.dialectProfile = Objects.requireNonNull(dialectProfile, "dialectProfile");
        this.recoveryEnabled = recoveryEnabled;
    }

    public DialectProfile getDialectProfile() {
        return dialectProfile;
    }

    public int getMaximumNestingDepth() {
        return maximumNestingDepth;
    }

    public int getMaximumTokenCount() {
        return maximumTokenCount;
    }

    public boolean isRecoveryEnabled() {
        return recoveryEnabled;
    }

    public int getMaximumAstNodeCount() {
        return maximumAstNodeCount;
    }

    public int getMaximumAstDepth() {
        return maximumAstDepth;
    }
}
