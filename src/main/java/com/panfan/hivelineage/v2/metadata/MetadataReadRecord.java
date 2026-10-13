package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import java.util.Optional;

/** Captured workflow evidence; no service or cache callback is stored here. */
public final class MetadataReadRecord {
    private final MetadataCacheKey key;
    private final LookupStatus status;
    private final MetadataProvenance provenance;
    private final ProviderCapabilities.LookupShape lookupShape;
    private final MetadataVersion metadataVersion;
    private final Optional<String> readId;

    public MetadataReadRecord(MetadataCacheKey key, LookupStatus status,
                              MetadataProvenance provenance, ProviderCapabilities.LookupShape lookupShape) {
        this(key, status, provenance, lookupShape, MetadataVersion.unknown(), Optional.<String>empty());
    }

    public MetadataReadRecord(MetadataCacheKey key, LookupStatus status,
                              MetadataProvenance provenance, ProviderCapabilities.LookupShape lookupShape,
                              MetadataVersion metadataVersion, Optional<String> readId) {
        this.key = ModelChecks.value(key, "key");
        this.status = ModelChecks.value(status, "status");
        this.provenance = ModelChecks.value(provenance, "provenance");
        this.lookupShape = ModelChecks.value(lookupShape, "lookupShape");
        this.metadataVersion = ModelChecks.value(metadataVersion, "metadataVersion");
        this.readId = ModelChecks.value(readId, "readId");
        if (readId.isPresent()) {
            ModelChecks.text(readId.get(), "readId value");
        }
    }

    public MetadataCacheKey getKey() {
        return key;
    }

    public LookupStatus getStatus() {
        return status;
    }

    public MetadataProvenance getProvenance() {
        return provenance;
    }

    public ProviderCapabilities.LookupShape getLookupShape() {
        return lookupShape;
    }

    public MetadataVersion getMetadataVersion() {
        return metadataVersion;
    }

    public Optional<String> getReadId() {
        return readId;
    }
}
