package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class StatementCounts {
    private final int total;
    private final int ignored;
    private final int failed;

    public StatementCounts(int total, int ignored, int failed) {
        this.total = ModelChecks.nonNegative(total, "total");
        this.ignored = ModelChecks.nonNegative(ignored, "ignored");
        this.failed = ModelChecks.nonNegative(failed, "failed");
        if ((long) ignored + failed > total) {
            throw new IllegalArgumentException("ignored and failed counts exceed total");
        }
    }

    public int getTotal() {
        return total;
    }

    public int getIgnored() {
        return ignored;
    }

    public int getFailed() {
        return failed;
    }
}
