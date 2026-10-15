package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class ResourceCommandSyntax extends CommandSyntax {
    private final ResourceCommandKindSyntax kind;
    private final String resourceType;
    private final List<String> resources;

    public ResourceCommandSyntax(ResourceCommandKindSyntax kind, String resourceType, List<String> resources) {
        super();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.resources = ModelCollections.immutableList(resources);
    }

    public ResourceCommandKindSyntax getKind() {
        return kind;
    }

    public String getResourceType() {
        return resourceType;
    }

    public List<String> getResources() {
        return resources;
    }
}
