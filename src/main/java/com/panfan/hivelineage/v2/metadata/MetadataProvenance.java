package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

/**
 * Describes recorded evidence; it does not claim global snapshot consistency.
 */
public final class MetadataProvenance {
    public enum Origin {
        ORIGINAL_DEFINITION,
        SYNTHESIZED_DEFINITION,
        PROVIDER_DEFINED,
        SESSION_DEFINED,
        TEST_FIXTURE
    }

    private final String sourceId;
    private final Origin origin;
    private final Optional<String> contentFingerprint;
    private final Optional<Long> readAtEpochMillis;

    public MetadataProvenance(String sourceId, Origin origin, Optional<String> contentFingerprint,
                              Optional<Long> readAtEpochMillis) {
        this.sourceId = ModelChecks.text(sourceId, "sourceId");
        this.origin = ModelChecks.value(origin, "origin");
        this.contentFingerprint = ModelChecks.value(contentFingerprint, "contentFingerprint");
        this.readAtEpochMillis = ModelChecks.value(readAtEpochMillis, "readAtEpochMillis");
        if (contentFingerprint.isPresent()) {
            ModelChecks.text(contentFingerprint.get(), "fingerprint");
        }
        if (readAtEpochMillis.isPresent() && readAtEpochMillis.get() < 0L) {
            throw new IllegalArgumentException("read time must not be negative");
        }
    }

    public String getSourceId() {
        return sourceId;
    }

    public Origin getOrigin() {
        return origin;
    }

    public Optional<String> getContentFingerprint() {
        return contentFingerprint;
    }

    public Optional<Long> getReadAtEpochMillis() {
        return readAtEpochMillis;
    }
}

