package com.panfan.hivelineage.v2.ir.quality;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Coverage is explicit per region and requirement; absence is not completion.
 */
public final class BindingCoverage {
    public enum State {NOT_REQUESTED, COMPLETE, PARTIAL, UNKNOWN}

    private final Map<NodeId, Map<BindingRequirement, State>> regions;

    public BindingCoverage(Map<NodeId, Map<BindingRequirement, State>> regions) {
        LinkedHashMap<NodeId, Map<BindingRequirement, State>> copy = new LinkedHashMap<NodeId, Map<BindingRequirement, State>>();
        for (Map.Entry<NodeId, Map<BindingRequirement, State>> entry : Objects.requireNonNull(regions, "regions").entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "region"), ModelCollections.immutableMap(entry.getValue()));
        }
        this.regions = ModelCollections.immutableMap(copy);
    }

    public Map<NodeId, Map<BindingRequirement, State>> getRegions() {
        return regions;
    }
}
