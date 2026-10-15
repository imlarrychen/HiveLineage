package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ResourceLimitException;

/**
 * Request-owned counter for actual provider invocations; zero forbids external calls.
 */
public final class ExternalReadBudget {
    private final int maximumReads;
    private int consumed;

    public ExternalReadBudget(int maximumReads) {
        if (maximumReads < 0) {
            throw new IllegalArgumentException("maximumReads must not be negative");
        }
        this.maximumReads = maximumReads;
    }

    public synchronized void beforeRead() {
        if (consumed >= maximumReads) {
            throw new ResourceLimitException("External read budget exhausted (maximum=" + maximumReads + ")");
        }
        consumed++;
    }

    public synchronized int getConsumed() {
        return consumed;
    }
}
