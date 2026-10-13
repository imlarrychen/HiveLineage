package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import java.util.Optional;

/** Captures the actual immutable metadata payload and its explicit lookup outcome. */
public final class RecordedLookup<T> {
    private final MetadataCacheKey key;
    private final Class<T> payloadType;
    private final LookupResult<T> result;
    private final Optional<ProviderCapabilities.LookupShape> lookupShape;

    public RecordedLookup(MetadataCacheKey key, Class<T> payloadType, LookupResult<T> result) {
        this(key, payloadType, result, Optional.<ProviderCapabilities.LookupShape>empty());
    }

    public RecordedLookup(MetadataCacheKey key, Class<T> payloadType, LookupResult<T> result,
                          ProviderCapabilities.LookupShape lookupShape) {
        this(key, payloadType, result, Optional.of(lookupShape));
    }

    private RecordedLookup(MetadataCacheKey key, Class<T> payloadType, LookupResult<T> result,
                           Optional<ProviderCapabilities.LookupShape> lookupShape) {
        this.key = ModelChecks.value(key, "key");
        this.payloadType = ModelChecks.value(payloadType, "payloadType");
        this.result = ModelChecks.value(result, "result");
        this.lookupShape = lookupShape;
        if (result.getValue().isPresent() && !payloadType.isInstance(result.getValue().get())) {
            throw new IllegalArgumentException("lookup payload must match its declared type");
        }
    }

    public MetadataCacheKey getKey() {
        return key;
    }

    public Class<T> getPayloadType() {
        return payloadType;
    }

    public LookupResult<T> getResult() {
        return result;
    }

    public Optional<ProviderCapabilities.LookupShape> getLookupShape() {
        return lookupShape;
    }
}
