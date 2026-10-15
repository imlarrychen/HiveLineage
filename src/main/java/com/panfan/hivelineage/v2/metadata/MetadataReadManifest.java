package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

/**
 * Immutable captured read evidence; creation does not perform or freeze any lookup.
 */
public final class MetadataReadManifest {
    public enum Consistency {
        PROVIDER_SNAPSHOT,
        PER_OBJECT_CAPTURE,
        UNKNOWN
    }

    private final MetadataReadContext readContext;
    private final List<MetadataReadRecord> records;
    private final Consistency consistency;

    public MetadataReadManifest(MetadataReadContext readContext, List<MetadataReadRecord> records,
                                Consistency consistency) {
        this.readContext = ModelChecks.value(readContext, "readContext");
        this.records = ModelCollections.immutableList(records);
        this.consistency = ModelChecks.value(consistency, "consistency");
        if (consistency == Consistency.PROVIDER_SNAPSHOT && !readContext.getSnapshotId().isPresent()) {
            throw new IllegalArgumentException("provider snapshot consistency requires a snapshot identity");
        }
        for (MetadataReadRecord record : this.records) {
            if (!readContext.equals(record.getKey().getReadContext())) {
                throw new IllegalArgumentException("read records must belong to the manifest context");
            }
        }
    }

    public MetadataReadContext getReadContext() {
        return readContext;
    }

    public List<MetadataReadRecord> getRecords() {
        return records;
    }

    public Consistency getConsistency() {
        return consistency;
    }
}
