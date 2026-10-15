package com.panfan.hivelineage.v2.metadata;

/**
 * Replay service construction only; no lookup or fallback implementation is supplied.
 */
public interface MetadataReplayProviderFactory {
    MetadataAccess create(MetadataSnapshot snapshot);
}
