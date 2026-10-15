package com.panfan.hivelineage.v2.metadata;

/**
 * Recording lifecycle contract only; implementations pin one read context.
 */
public interface MetadataRecorder {
    void record(RecordedLookup<?> lookup);

    MetadataSnapshot freeze();
}
