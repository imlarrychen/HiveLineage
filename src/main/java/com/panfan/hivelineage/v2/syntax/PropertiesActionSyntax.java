package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Map;

public final class PropertiesActionSyntax extends AlterActionSyntax {
    private final Map<String, String> properties;

    public PropertiesActionSyntax(Map<String, String> properties) {
        super();
        this.properties = ModelCollections.immutableMap(properties);
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
