package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

/**
 * Adapter input pins the caller cache to one provider source and access context.
 */
public final class LegacyMetadataBinding {
    private final Optional<HiveMetadataProvider> provider;
    private final Optional<HiveMetadataCache> cache;
    private final MetadataReadContext readContext;
    private final boolean authoritativeNullMeansNotFound;

    public LegacyMetadataBinding(Optional<HiveMetadataProvider> provider, Optional<HiveMetadataCache> cache,
                                 MetadataReadContext readContext, boolean authoritativeNullMeansNotFound) {
        this.provider = ModelChecks.value(provider, "provider");
        this.cache = ModelChecks.value(cache, "cache");
        this.readContext = ModelChecks.value(readContext, "readContext");
        this.authoritativeNullMeansNotFound = authoritativeNullMeansNotFound;
    }

    public Optional<HiveMetadataProvider> getProvider() {
        return provider;
    }

    public Optional<HiveMetadataCache> getCache() {
        return cache;
    }

    public MetadataReadContext getReadContext() {
        return readContext;
    }

    public boolean isAuthoritativeNullMeansNotFound() {
        return authoritativeNullMeansNotFound;
    }
}

