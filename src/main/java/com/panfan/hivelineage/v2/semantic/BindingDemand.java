package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import java.util.Set;

public final class BindingDemand {
    private final Set<BindingRequirement> requirements;
    private final Set<NodeId> regions;

    public BindingDemand(Set<BindingRequirement> requirements, Set<NodeId> regions) {
        this.requirements = ModelCollections.immutableSet(requirements);
        this.regions = ModelCollections.immutableSet(regions);
    }

    public Set<BindingRequirement> getRequirements() {
        return requirements;
    }

    public Set<NodeId> getRegions() {
        return regions;
    }
}
