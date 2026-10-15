package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

/** Legacy name mapping is selected only for a star with explicit target fields. */
final class LegacyTargetMapping implements SinkMappingStrategy {
    @Override
    public MappingPlan map(BoundRelation query, SinkBindingRequest request, TargetLayout layout) {
        if (request.isSourceContainsStar() && !request.getExplicitTargetFields().isEmpty()) {
            return SinkMappingSupport.named(query, request, layout);
        }
        return SinkMappingSupport.positional(query, request, layout, false);
    }
}
