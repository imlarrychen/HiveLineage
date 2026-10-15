package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Set;

public final class ProviderCapabilities {
    public enum LookupShape {
        UNKNOWN,
        FACET_LOOKUP,
        BUNDLED_LOOKUP
    }

    private final Set<MetadataFacet> facets;
    private final LookupShape lookupShape;
    private final boolean authoritativeNotFound;

    public ProviderCapabilities(Set<MetadataFacet> facets, LookupShape lookupShape, boolean authoritativeNotFound) {
        this.facets = ModelCollections.immutableSet(facets);
        this.lookupShape = ModelChecks.value(lookupShape, "lookupShape");
        this.authoritativeNotFound = authoritativeNotFound;
    }

    public Set<MetadataFacet> getFacets() {
        return facets;
    }

    public LookupShape getLookupShape() {
        return lookupShape;
    }

    public boolean isAuthoritativeNotFound() {
        return authoritativeNotFound;
    }

    public boolean supports(MetadataFacet facet) {
        return facets.contains(ModelChecks.value(facet, "facet"));
    }
}
