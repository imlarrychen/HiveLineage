package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

/**
 * Source mapping data only; resolving an offset is delegated to SourceMapper.
 */
public final class SourceMap {
    private final String originalSourceId;
    private final String expandedSourceId;
    private final List<SourceMapEntry> entries;

    public SourceMap(String originalSourceId, String expandedSourceId, List<SourceMapEntry> entries) {
        this.originalSourceId = Objects.requireNonNull(originalSourceId, "originalSourceId");
        this.expandedSourceId = Objects.requireNonNull(expandedSourceId, "expandedSourceId");
        this.entries = ModelCollections.immutableList(entries);
    }

    public String getOriginalSourceId() {
        return originalSourceId;
    }

    public String getExpandedSourceId() {
        return expandedSourceId;
    }

    public List<SourceMapEntry> getEntries() {
        return entries;
    }
}
