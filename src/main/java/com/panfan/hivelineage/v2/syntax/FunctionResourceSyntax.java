package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class FunctionResourceSyntax {
    private final String resourceType;
    private final String uri;

    public FunctionResourceSyntax(String resourceType, String uri) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.uri = Objects.requireNonNull(uri, "uri");
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getUri() {
        return uri;
    }
}
