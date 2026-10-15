package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;

/**
 * Per-object lookup shape permits mixed captured sources to retain their actual read origin.
 */
public interface MetadataLookupProvenance {
    ProviderCapabilities getCapabilities();

    default ProviderCapabilities.LookupShape getLookupShape(QualifiedName name, MetadataFacet facet) {
        return getCapabilities().getLookupShape();
    }
}
