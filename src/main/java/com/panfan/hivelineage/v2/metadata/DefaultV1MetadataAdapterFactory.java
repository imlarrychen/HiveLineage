package com.panfan.hivelineage.v2.metadata;

public final class DefaultV1MetadataAdapterFactory implements V1MetadataAdapterFactory {
    @Override
    public MetadataProvider adapt(LegacyMetadataBinding binding) {
        return new V1MetadataProviderAdapter(binding);
    }
}
