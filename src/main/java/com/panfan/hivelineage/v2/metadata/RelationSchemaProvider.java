package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;

public interface RelationSchemaProvider extends MetadataLookupProvenance {
    ProviderCapabilities getCapabilities();

    LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context);
}
