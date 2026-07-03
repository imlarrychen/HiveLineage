package com.panfan.hivelineage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HiveMetadataCache {
    private final Map<TableIdentifier, CacheEntry> tableMetadataCache = new ConcurrentHashMap<>();
    private final Map<TableIdentifier, Long> missingTableMetadataCache = new ConcurrentHashMap<>();
    private final Map<TableIdentifier, Object> loadLocks = new ConcurrentHashMap<>();
    private final long expireAfterWriteMillis;

    public HiveMetadataCache() {
        this(0L);
    }

    public HiveMetadataCache(long expireAfterWriteMillis) {
        this.expireAfterWriteMillis = Math.max(0L, expireAfterWriteMillis);
    }

    public HiveTableMetadata getTableMetadata(TableIdentifier tableIdentifier, HiveMetadataProvider metadataProvider) {
        if (metadataProvider == null) {
            return null;
        }
        HiveTableMetadata cached = getCachedTableMetadata(tableIdentifier);
        if (cached != null) {
            return cached;
        }
        if (isMissingCached(tableIdentifier)) {
            return null;
        }

        Object lock = loadLocks.computeIfAbsent(tableIdentifier, key -> new Object());
        synchronized (lock) {
            try {
                cached = getCachedTableMetadata(tableIdentifier);
                if (cached != null) {
                    return cached;
                }
                if (isMissingCached(tableIdentifier)) {
                    return null;
                }
                HiveTableMetadata loaded = metadataProvider.getTableMetadata(tableIdentifier);
                long now = now();
                if (loaded != null) {
                    tableMetadataCache.put(tableIdentifier, new CacheEntry(loaded, now));
                    missingTableMetadataCache.remove(tableIdentifier);
                } else {
                    missingTableMetadataCache.put(tableIdentifier, now);
                }
                return loaded;
            } finally {
                loadLocks.remove(tableIdentifier, lock);
            }
        }
    }

    public int size() {
        return tableMetadataCache.size() + missingTableMetadataCache.size();
    }

    private HiveTableMetadata getCachedTableMetadata(TableIdentifier tableIdentifier) {
        CacheEntry cached = tableMetadataCache.get(tableIdentifier);
        if (cached == null) {
            return null;
        }
        if (isExpired(cached.getLoadedAtMillis())) {
            tableMetadataCache.remove(tableIdentifier, cached);
            return null;
        }
        return cached.getMetadata();
    }

    private boolean isMissingCached(TableIdentifier tableIdentifier) {
        Long loadedAtMillis = missingTableMetadataCache.get(tableIdentifier);
        if (loadedAtMillis == null) {
            return false;
        }
        if (isExpired(loadedAtMillis)) {
            missingTableMetadataCache.remove(tableIdentifier, loadedAtMillis);
            return false;
        }
        return true;
    }

    private boolean isExpired(long loadedAtMillis) {
        return expireAfterWriteMillis > 0L && now() - loadedAtMillis >= expireAfterWriteMillis;
    }

    private long now() {
        return System.currentTimeMillis();
    }

    private static final class CacheEntry {
        private final HiveTableMetadata metadata;
        private final long loadedAtMillis;

        private CacheEntry(HiveTableMetadata metadata, long loadedAtMillis) {
            this.metadata = metadata;
            this.loadedAtMillis = loadedAtMillis;
        }

        private HiveTableMetadata getMetadata() {
            return metadata;
        }

        private long getLoadedAtMillis() {
            return loadedAtMillis;
        }
    }
}
