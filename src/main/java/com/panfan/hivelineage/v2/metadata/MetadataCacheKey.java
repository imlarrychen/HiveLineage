package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.Objects;

/** Source-scoped key shared across task versions, with independent capability facets. */
public final class MetadataCacheKey {
    private final MetadataReadContext readContext;
    private final QualifiedName objectName;
    private final MetadataFacet facet;

    public MetadataCacheKey(MetadataReadContext readContext, QualifiedName objectName, MetadataFacet facet) {
        this.readContext = ModelChecks.value(readContext, "readContext");
        this.objectName = ModelChecks.value(objectName, "objectName");
        this.facet = ModelChecks.value(facet, "facet");
    }

    public MetadataReadContext getReadContext() {
        return readContext;
    }

    public QualifiedName getObjectName() {
        return objectName;
    }

    public MetadataFacet getFacet() {
        return facet;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MetadataCacheKey)) {
            return false;
        }
        MetadataCacheKey that = (MetadataCacheKey) other;
        return readContext.equals(that.readContext) && objectName.equals(that.objectName) && facet == that.facet;
    }

    @Override
    public int hashCode() {
        return Objects.hash(readContext, objectName, facet);
    }
}

