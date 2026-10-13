package com.panfan.hivelineage.v2.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe read capture. The first value pins every facet within this batch. */
public final class SnapshotMetadataRecorder implements MetadataRecorder {
    private final MetadataReadContext context;
    private final Map<MetadataCacheKey, RecordedLookup<?>> reads = new LinkedHashMap<>();

    public SnapshotMetadataRecorder(MetadataReadContext context) {
        this.context = java.util.Objects.requireNonNull(context, "context");
    }

    @Override
    public synchronized void record(RecordedLookup<?> lookup) {
        if (!context.equals(lookup.getKey().getReadContext())) {
            throw new IllegalArgumentException("recording context mismatch");
        }
        RecordedLookup<?> prior = reads.get(lookup.getKey());
        if (prior != null && !prior.getPayloadType().equals(lookup.getPayloadType())) {
            throw new IllegalArgumentException("recording payload type mismatch");
        }
        reads.putIfAbsent(lookup.getKey(), lookup);
    }

    @Override
    public synchronized MetadataSnapshot freeze() {
        return new MetadataSnapshot(context, new ArrayList<>(reads.values()));
    }
}
