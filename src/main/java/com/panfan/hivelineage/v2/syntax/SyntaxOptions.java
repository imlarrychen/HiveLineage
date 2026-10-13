package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.dialect.DialectProfile;
import java.util.Objects;

public final class SyntaxOptions {
    private final DialectProfile dialectProfile;
    private final boolean recoveryEnabled;
    private final int maximumNestingDepth;
    private final int maximumTokenCount;

    public SyntaxOptions(DialectProfile dialectProfile, boolean recoveryEnabled) {
        this(dialectProfile, recoveryEnabled, 512, 2000000);
    }

    public SyntaxOptions(DialectProfile dialectProfile, boolean recoveryEnabled, int maximumNestingDepth, int maximumTokenCount) {
        if (maximumNestingDepth < 0 || maximumTokenCount < 0) {
            throw new IllegalArgumentException("parser resource limits must be non-negative");
        }
        this.maximumNestingDepth = maximumNestingDepth;
        this.maximumTokenCount = maximumTokenCount;
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
}
