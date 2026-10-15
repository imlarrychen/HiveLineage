package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata source and access scope; intentionally has no workflow/task version.
 */
public final class MetadataReadContext {
    private final String sourceId;
    private final String clusterName;
    private final Optional<String> snapshotId;
    private final Map<String, String> accessContext;

    public MetadataReadContext(String sourceId, String clusterName, Optional<String> snapshotId,
                               Map<String, String> accessContext) {
        this.sourceId = ModelChecks.text(sourceId, "sourceId");
        this.clusterName = ModelChecks.text(clusterName, "clusterName");
        this.snapshotId = ModelChecks.value(snapshotId, "snapshotId");
        if (snapshotId.isPresent()) {
            ModelChecks.text(snapshotId.get(), "snapshotId value");
        }
        this.accessContext = ModelCollections.immutableMap(accessContext);
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getClusterName() {
        return clusterName;
    }

    public Optional<String> getSnapshotId() {
        return snapshotId;
    }

    public Map<String, String> getAccessContext() {
        return accessContext;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MetadataReadContext)) {
            return false;
        }
        MetadataReadContext that = (MetadataReadContext) other;
        return sourceId.equals(that.sourceId) && clusterName.equals(that.clusterName)
                && snapshotId.equals(that.snapshotId) && accessContext.equals(that.accessContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, clusterName, snapshotId, accessContext);
    }
}

