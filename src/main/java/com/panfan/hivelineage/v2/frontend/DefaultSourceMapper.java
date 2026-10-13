package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;

public final class DefaultSourceMapper implements SourceMapper {
    @Override
    public SourceRef mapToOriginal(SourceMap sourceMap, SourceSpan expandedSpan) {
        if (!sourceMap.getExpandedSourceId().equals(expandedSpan.getSourceId())) {
            throw new IllegalArgumentException("range does not belong to the expanded source");
        }
        int start = Integer.MAX_VALUE;
        int end = -1;
        for (SourceMapEntry entry : sourceMap.getEntries()) {
            SourceSpan range = entry.getExpandedSpan();
            if (expandedSpan.getEnd() <= range.getStart() || expandedSpan.getStart() >= range.getEnd()) {
                continue;
            }
            int originalStart = entry.getOriginalSpan().getStart();
            int originalEnd = entry.getOriginalSpan().getEnd();
            if (entry.getKind() == SourceMappingKind.UNCHANGED) {
                originalStart += Math.max(0, expandedSpan.getStart() - range.getStart());
                originalEnd -= Math.max(0, range.getEnd() - expandedSpan.getEnd());
            }
            start = Math.min(start, originalStart);
            end = Math.max(end, originalEnd);
        }
        if (end < 0) {
            return SourceRef.synthetic(sourceMap.getOriginalSourceId());
        }
        return new SourceRef(sourceMap.getOriginalSourceId(), new SourceSpan(sourceMap.getOriginalSourceId(), start, end));
    }
}
