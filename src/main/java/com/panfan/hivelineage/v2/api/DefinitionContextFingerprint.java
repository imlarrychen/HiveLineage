package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.metadata.DefinitionContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Every component is length-delimited; map display strings are not identity encodings.
 */
final class DefinitionContextFingerprint {
    private DefinitionContextFingerprint() {
    }

    static String of(DefinitionContext context, String sourceId, String expandedText) {
        StringBuilder key = new StringBuilder();
        key.append(context.getDefaultDatabase().isPresent() ? '1' : '0');
        if (context.getDefaultDatabase().isPresent()) {
            key.append(context.getDefaultDatabase().get().getParts().size()).append(':');
            for (String part : context.getDefaultDatabase().get().getParts()) {
                component(key, part);
            }
        }
        key.append(context.getVariables().size()).append(':');
        for (Map.Entry<String, String> entry : new TreeMap<String, String>(context.getVariables()).entrySet()) {
            component(key, entry.getKey());
            component(key, entry.getValue());
        }
        component(key, sourceId);
        component(key, expandedText);
        return UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void component(StringBuilder key, String value) {
        key.append(value.length()).append(':').append(value);
    }
}
