package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Map;
import java.util.Objects;

public final class SerdeSyntax {
    private final String className;
    private final Map<String, String> properties;

    public SerdeSyntax(String className, Map<String, String> properties) {
        this.className = Objects.requireNonNull(className, "className");
        this.properties = ModelCollections.immutableMap(properties);
    }

    public String getClassName() {
        return className;
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
