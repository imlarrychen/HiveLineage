package com.panfan.hivelineage.v2.metadata;

/**
 * Conversion/loading is deferred; this stage declares the adapter boundary only.
 */
public interface V1MetadataAdapterFactory {
    MetadataProvider adapt(LegacyMetadataBinding binding);
}

