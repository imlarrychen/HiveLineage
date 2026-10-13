package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;

/** Optional light lookup which does not silently load the schema facet. */
public interface ObjectDescriptorProvider extends MetadataLookupProvenance {
    ProviderCapabilities getCapabilities();

    LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context);
}
