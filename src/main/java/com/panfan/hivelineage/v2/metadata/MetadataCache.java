package com.panfan.hivelineage.v2.metadata;

import java.util.Optional;

/**
 * Cache protocol only. Empty Optional means a miss, not NOT_FOUND.
 * Implementations retain the payload type and never turn failures into absent objects.
 */
public interface MetadataCache {
    <T> Optional<LookupResult<T>> get(MetadataCacheKey key, Class<T> payloadType);

    default <T> Optional<RecordedLookup<T>> getRecordedLookup(MetadataCacheKey key, Class<T> payloadType) {
        return get(key, payloadType).map(result -> new RecordedLookup<>(key, payloadType, result, ProviderCapabilities.LookupShape.UNKNOWN));
    }

    <T> void put(MetadataCacheKey key, Class<T> payloadType, LookupResult<T> result, long ttlMillis);

    default Optional<ProviderCapabilities.LookupShape> getLookupShape(MetadataCacheKey key) {
        return Optional.empty();
    }

    default <T> void put(MetadataCacheKey key, Class<T> payloadType, LookupResult<T> result, long ttlMillis,
                        ProviderCapabilities.LookupShape lookupShape) {
        put(key, payloadType, result, ttlMillis);
    }
}
