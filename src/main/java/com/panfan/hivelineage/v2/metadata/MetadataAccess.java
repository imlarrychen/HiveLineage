package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;

/** Binder-facing facet access; callers request only the metadata they actually need. */
public interface MetadataAccess {
    LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context);

    LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context);

    LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context);
}
