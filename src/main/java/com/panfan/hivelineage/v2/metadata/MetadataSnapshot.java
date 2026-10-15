package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

/**
 * Captured payloads for deterministic replay, distinct from status-only read evidence.
 */
public final class MetadataSnapshot {
    private final MetadataReadContext readContext;
    private final List<RecordedLookup<?>> lookups;

    public MetadataSnapshot(MetadataReadContext readContext, List<RecordedLookup<?>> lookups) {
        this.readContext = ModelChecks.value(readContext, "readContext");
        this.lookups = ModelCollections.immutableList(lookups);
        for (RecordedLookup<?> lookup : this.lookups) {
            if (!readContext.equals(lookup.getKey().getReadContext())) {
                throw new IllegalArgumentException("recorded lookups must belong to the snapshot read context");
            }
        }
    }

    public MetadataReadContext getReadContext() {
        return readContext;
    }

    public List<RecordedLookup<?>> getLookups() {
        return lookups;
    }
}
