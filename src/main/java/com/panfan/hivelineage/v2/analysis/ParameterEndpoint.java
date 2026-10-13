package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class ParameterEndpoint implements LineageEndpoint {
    private final String namespace;
    private final String name;

    public ParameterEndpoint(String namespace, String name) {
        this.namespace = ModelChecks.text(namespace, "namespace");
        this.name = ModelChecks.text(name, "name");
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.PARAMETER;
    }
}
