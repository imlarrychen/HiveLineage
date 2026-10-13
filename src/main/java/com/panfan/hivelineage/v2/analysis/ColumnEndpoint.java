package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import java.util.List;

public final class ColumnEndpoint implements LineageEndpoint {
    private final ObjectId objectId;
    private final FieldId fieldId;
    private final List<String> fieldPath;

    public ColumnEndpoint(ObjectId objectId, FieldId fieldId, List<String> fieldPath) {
        this.objectId = ModelChecks.value(objectId, "objectId");
        this.fieldId = ModelChecks.value(fieldId, "fieldId");
        this.fieldPath = ModelCollections.immutableList(fieldPath);
    }

    public ObjectId getObjectId() {
        return objectId;
    }

    public FieldId getFieldId() {
        return fieldId;
    }

    public List<String> getFieldPath() {
        return fieldPath;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.COLUMN;
    }
}
