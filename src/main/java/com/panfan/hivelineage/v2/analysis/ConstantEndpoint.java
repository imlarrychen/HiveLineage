package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class ConstantEndpoint implements LineageEndpoint {
    private final String valueRepresentation;

    public ConstantEndpoint(String valueRepresentation) {
        this.valueRepresentation = ModelChecks.text(valueRepresentation, "valueRepresentation");
    }

    public String getValueRepresentation() {
        return valueRepresentation;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.CONSTANT;
    }
}
