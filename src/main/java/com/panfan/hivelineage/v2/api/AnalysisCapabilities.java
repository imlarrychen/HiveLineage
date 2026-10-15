package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Set;

public final class AnalysisCapabilities {
    private final Set<AnalysisCapability> capabilities;

    public AnalysisCapabilities(Set<AnalysisCapability> capabilities) {
        this.capabilities = ModelCollections.immutableSet(capabilities);
    }

    public boolean supports(AnalysisCapability capability) {
        return capabilities.contains(java.util.Objects.requireNonNull(capability, "capability"));
    }

    public Set<AnalysisCapability> getCapabilities() {
        return capabilities;
    }
}
