package com.panfan.hivelineage.v2.metadata;

/** Declares how workflow services are exposed through the capability-aware read boundary. */
public interface MetadataAccessFactory {
    MetadataAccess create(WorkflowContext workflow);
}
