package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.StarMappingPolicy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Registration changes are independent of the planner's layout and publication flow. */
final class SinkMappingStrategies {
    private SinkMappingStrategies() {
    }

    static Map<StarMappingPolicy, SinkMappingStrategy> configured(Map<StarMappingPolicy, SinkMappingStrategy> registrations) {
        EnumMap<StarMappingPolicy, SinkMappingStrategy> configured = new EnumMap<>(StarMappingPolicy.class);
        configured.put(StarMappingPolicy.POSITIONAL_EXACT, new PositionalTargetMapping());
        configured.put(StarMappingPolicy.SPEC_TARGET_SCHEMA_MAPPING, new SpecTargetMapping());
        configured.put(StarMappingPolicy.EXPLICIT_LEGACY_TARGET_MAPPING, new LegacyTargetMapping());
        configured.put(StarMappingPolicy.CURRENT_API_TARGET_MAPPING, new CurrentApiTargetMapping());
        for (Map.Entry<StarMappingPolicy, SinkMappingStrategy> registration : registrations.entrySet()) {
            configured.put(Objects.requireNonNull(registration.getKey(), "policy"),
                    Objects.requireNonNull(registration.getValue(), "strategy"));
        }
        return Collections.unmodifiableMap(configured);
    }
}
