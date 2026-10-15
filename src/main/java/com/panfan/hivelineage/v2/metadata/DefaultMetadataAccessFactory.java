package com.panfan.hivelineage.v2.metadata;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * One pinned read boundary per workflow object, with no task Session shared.
 */
public final class DefaultMetadataAccessFactory implements WorkflowMetadataAccessFactory {
    public static final long DEFAULT_CACHE_TTL_MILLIS = 300_000L;
    private final Map<WorkflowContext, CapturedMetadataAccess> batches = new WeakHashMap<>();
    private final long ttlMillis;

    public DefaultMetadataAccessFactory() {
        this(DEFAULT_CACHE_TTL_MILLIS);
    }

    public DefaultMetadataAccessFactory(long ttlMillis) {
        if (ttlMillis < 0L) {
            throw new IllegalArgumentException("negative metadata ttl");
        }
        this.ttlMillis = ttlMillis;
    }

    @Override
    public synchronized CapturedMetadataAccess create(WorkflowContext workflow) {
        CapturedMetadataAccess access = batches.get(workflow);
        if (access == null) {
            access = new CapturedMetadataAccess(workflow, ttlMillis);
            batches.put(workflow, access);
        }
        return access;
    }
}
