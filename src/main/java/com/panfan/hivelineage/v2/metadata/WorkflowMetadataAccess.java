package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;

/**
 * Captured workflow reads, including their budget and audit capabilities.
 */
public interface WorkflowMetadataAccess extends MetadataAccess {
    LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context, ExternalReadBudget budget);

    LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context, ExternalReadBudget budget);

    LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context, ExternalReadBudget budget);

    MetadataReadManifest manifest();
}
