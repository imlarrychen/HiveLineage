package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import java.util.List;

public final class SinkBindingRequest {
    private final NodeId writeId;
    private final ObjectId targetObject;
    private final OutputSchema targetSchema;
    private final List<FieldIdentity> explicitTargetFields;
    private final PartitionScope partitionScope;
    private final BoundWrite.WriteMode writeMode;
    private final VersionRef beforeVersion;
    private final VersionRef afterVersion;
    private final StarMappingPolicy mappingPolicy;
    private final boolean sourceContainsStar;

    public SinkBindingRequest(NodeId writeId, ObjectId targetObject, OutputSchema targetSchema, List<FieldIdentity> explicitTargetFields, PartitionScope partitionScope, BoundWrite.WriteMode writeMode, VersionRef beforeVersion, VersionRef afterVersion, StarMappingPolicy mappingPolicy) {
        this(writeId, targetObject, targetSchema, explicitTargetFields, partitionScope, writeMode,
            beforeVersion, afterVersion, mappingPolicy, false);
    }

    public SinkBindingRequest(NodeId writeId, ObjectId targetObject, OutputSchema targetSchema, List<FieldIdentity> explicitTargetFields, PartitionScope partitionScope, BoundWrite.WriteMode writeMode, VersionRef beforeVersion, VersionRef afterVersion, StarMappingPolicy mappingPolicy, boolean sourceContainsStar) {
        this.writeId = ModelChecks.value(writeId, "writeId");
        this.targetObject = ModelChecks.value(targetObject, "targetObject");
        this.targetSchema = ModelChecks.value(targetSchema, "targetSchema");
        this.explicitTargetFields = ModelCollections.immutableList(explicitTargetFields);
        this.partitionScope = ModelChecks.value(partitionScope, "partitionScope");
        this.writeMode = ModelChecks.value(writeMode, "writeMode");
        this.beforeVersion = ModelChecks.value(beforeVersion, "beforeVersion");
        this.afterVersion = ModelChecks.value(afterVersion, "afterVersion");
        this.mappingPolicy = ModelChecks.value(mappingPolicy, "mappingPolicy");
        this.sourceContainsStar = sourceContainsStar;
    }

    public NodeId getWriteId() {
        return writeId;
    }

    public ObjectId getTargetObject() {
        return targetObject;
    }

    public OutputSchema getTargetSchema() {
        return targetSchema;
    }

    public List<FieldIdentity> getExplicitTargetFields() {
        return explicitTargetFields;
    }

    public PartitionScope getPartitionScope() {
        return partitionScope;
    }

    public BoundWrite.WriteMode getWriteMode() {
        return writeMode;
    }

    public VersionRef getBeforeVersion() {
        return beforeVersion;
    }

    public VersionRef getAfterVersion() {
        return afterVersion;
    }

    public StarMappingPolicy getMappingPolicy() {
        return mappingPolicy;
    }

    public boolean isSourceContainsStar() {
        return sourceContainsStar;
    }
}
