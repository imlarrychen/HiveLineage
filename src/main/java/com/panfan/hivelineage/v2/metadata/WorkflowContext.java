package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

/**
 * Shared services and read scope; no task Session is retained.
 */
public final class WorkflowContext {
    private final String workflowId;
    private final MetadataReadContext readContext;
    private final Optional<MetadataProvider> metadataProvider;
    private final MetadataCache metadataCache;
    private final Optional<ObjectDescriptorProvider> objectDescriptorProvider;
    private final Optional<RelationSchemaProvider> relationSchemaProvider;

    public WorkflowContext(String workflowId, MetadataReadContext readContext,
                           MetadataProvider metadataProvider, MetadataCache metadataCache) {
        this(workflowId, readContext, Optional.of(ModelChecks.value(metadataProvider, "metadataProvider")), metadataCache,
                Optional.<ObjectDescriptorProvider>empty(), Optional.<RelationSchemaProvider>empty());
    }

    public WorkflowContext(String workflowId, MetadataReadContext readContext,
                           MetadataProvider metadataProvider, MetadataCache metadataCache,
                           Optional<ObjectDescriptorProvider> objectDescriptorProvider,
                           Optional<RelationSchemaProvider> relationSchemaProvider) {
        this(workflowId, readContext, Optional.of(ModelChecks.value(metadataProvider, "metadataProvider")), metadataCache,
                objectDescriptorProvider, relationSchemaProvider);
    }

    public WorkflowContext(String workflowId, MetadataReadContext readContext,
                           Optional<MetadataProvider> metadataProvider, MetadataCache metadataCache,
                           Optional<ObjectDescriptorProvider> objectDescriptorProvider,
                           Optional<RelationSchemaProvider> relationSchemaProvider) {
        this.workflowId = ModelChecks.text(workflowId, "workflowId");
        this.readContext = ModelChecks.value(readContext, "readContext");
        this.metadataProvider = ModelChecks.value(metadataProvider, "metadataProvider");
        this.metadataCache = ModelChecks.value(metadataCache, "metadataCache");
        this.objectDescriptorProvider = ModelChecks.value(objectDescriptorProvider, "objectDescriptorProvider");
        this.relationSchemaProvider = ModelChecks.value(relationSchemaProvider, "relationSchemaProvider");
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public MetadataReadContext getReadContext() {
        return readContext;
    }

    public Optional<MetadataProvider> getMetadataProvider() {
        return metadataProvider;
    }

    public MetadataCache getMetadataCache() {
        return metadataCache;
    }

    public Optional<ObjectDescriptorProvider> getObjectDescriptorProvider() {
        return objectDescriptorProvider;
    }

    public Optional<RelationSchemaProvider> getRelationSchemaProvider() {
        return relationSchemaProvider;
    }
}
