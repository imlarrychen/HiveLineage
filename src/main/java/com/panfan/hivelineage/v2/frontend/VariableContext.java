package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Map;

/**
 * Explicit variable namespaces, including supplied environment and system values.
 */
public final class VariableContext {
    private final Map<String, Map<String, String>> namespaces;

    public VariableContext(Map<String, Map<String, String>> namespaces) {
        Map<String, Map<String, String>> copied = new java.util.LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, Map<String, String>> entry : namespaces.entrySet()) {
            copied.put(entry.getKey(), ModelCollections.immutableMap(entry.getValue()));
        }
        this.namespaces = ModelCollections.immutableMap(copied);
    }

    public Map<String, Map<String, String>> getNamespaces() {
        return namespaces;
    }

}
