package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Occurrence-qualified direct reference; self-join aliases remain distinct. */
public final class DirectFieldEndpoint implements LineageEndpoint {
    private final RelationInstanceId owner;
    private final FieldId fieldId;
    private final List<String> fieldPath;
    private final Optional<ObjectId> objectId;

    public DirectFieldEndpoint(RelationInstanceId owner, FieldId fieldId, List<String> fieldPath, Optional<ObjectId> objectId) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.fieldId = Objects.requireNonNull(fieldId, "fieldId");
        this.fieldPath = ModelCollections.immutableList(fieldPath);
        this.objectId = Objects.requireNonNull(objectId, "objectId");
    }

    public RelationInstanceId getOwner() {
        return owner;
    }

    public FieldId getFieldId() {
        return fieldId;
    }

    public List<String> getFieldPath() {
        return fieldPath;
    }

    public Optional<ObjectId> getObjectId() {
        return objectId;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.COLUMN;
    }
}
