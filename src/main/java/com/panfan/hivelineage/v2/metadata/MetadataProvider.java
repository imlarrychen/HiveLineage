package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;

/** All outcomes are explicit; implementations must never return null. */
public interface MetadataProvider extends MetadataLookupProvenance {
    ProviderCapabilities getCapabilities();

    LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context);
}
