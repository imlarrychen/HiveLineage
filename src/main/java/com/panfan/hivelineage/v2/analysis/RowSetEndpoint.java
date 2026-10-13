package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;

public final class RowSetEndpoint implements LineageEndpoint {
    private final RelationInstanceId relationId;

    public RowSetEndpoint(RelationInstanceId relationId) {
        this.relationId = ModelChecks.value(relationId, "relationId");
    }

    public RelationInstanceId getRelationId() {
        return relationId;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.ROWSET;
    }
}
