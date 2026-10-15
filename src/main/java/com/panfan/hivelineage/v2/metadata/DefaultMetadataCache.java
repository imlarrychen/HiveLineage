package com.panfan.hivelineage.v2.metadata;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Process cache. A batch additionally pins reads independently of expiry.
 */
public final class DefaultMetadataCache implements MetadataCache {
    private final ConcurrentMap<MetadataCacheKey, Entry> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public DefaultMetadataCache() {
        this(System::currentTimeMillis);
    }

    public DefaultMetadataCache(LongSupplier clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public <T> Optional<LookupResult<T>> get(MetadataCacheKey key, Class<T> type) {
        return getRecordedLookup(key, type).map(RecordedLookup::getResult);
    }

    @Override
    public <T> Optional<RecordedLookup<T>> getRecordedLookup(MetadataCacheKey key, Class<T> type) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt > 0L && clock.getAsLong() >= entry.expiresAt) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        if (!type.equals(entry.type)) {
            throw new IllegalArgumentException("metadata cache payload type mismatch");
        }
        return Optional.of(new RecordedLookup<>(key, type, DefaultMetadataCache.<T>cast(entry.result),
                entry.lookupShape.orElse(ProviderCapabilities.LookupShape.UNKNOWN)));
    }

    @Override
    public <T> void put(MetadataCacheKey key, Class<T> type, LookupResult<T> result, long ttlMillis) {
        putEntry(key, type, result, ttlMillis, Optional.<ProviderCapabilities.LookupShape>empty());
    }

    @Override
    public <T> void put(MetadataCacheKey key, Class<T> type, LookupResult<T> result, long ttlMillis,
                        ProviderCapabilities.LookupShape lookupShape) {
        putEntry(key, type, result, ttlMillis, Optional.of(java.util.Objects.requireNonNull(lookupShape, "lookupShape")));
    }

    @Override
    public Optional<ProviderCapabilities.LookupShape> getLookupShape(MetadataCacheKey key) {
        Entry entry = entries.get(key);
        if (entry == null || (entry.expiresAt > 0L && clock.getAsLong() >= entry.expiresAt)) {
            return Optional.empty();
        }
        return entry.lookupShape;
    }

    private <T> void putEntry(MetadataCacheKey key, Class<T> type, LookupResult<T> result, long ttlMillis,
                              Optional<ProviderCapabilities.LookupShape> lookupShape) {
        new RecordedLookup<>(key, type, result);
        if (ttlMillis < 0L) {
            throw new IllegalArgumentException("negative cache ttl");
        }
        if (result.getStatus() == LookupStatus.ACCESS_ERROR) {
            return;
        }
        long now = clock.getAsLong();
        long expires = ttlMillis == 0L ? 0L : now > Long.MAX_VALUE - ttlMillis ? Long.MAX_VALUE : now + ttlMillis;
        entries.put(key, new Entry(type, result, expires, lookupShape));
    }

    @SuppressWarnings("unchecked")
    private static <T> LookupResult<T> cast(LookupResult<?> value) {
        return (LookupResult<T>) value;
    }

    private static final class Entry {
        private final Class<?> type;
        private final LookupResult<?> result;
        private final long expiresAt;
        private final Optional<ProviderCapabilities.LookupShape> lookupShape;

        private Entry(Class<?> type, LookupResult<?> result, long expiresAt, Optional<ProviderCapabilities.LookupShape> lookupShape) {
            this.type = type;
            this.result = result;
            this.expiresAt = expiresAt;
            this.lookupShape = lookupShape;
        }
    }
}
