package com.panfan.hivelineage.v2.metadata;

/**
 * The analyzer needs audited, budget-aware reads, not a particular cache implementation.
 */
public interface WorkflowMetadataAccessFactory extends MetadataAccessFactory {
    @Override
    WorkflowMetadataAccess create(WorkflowContext workflow);
}
