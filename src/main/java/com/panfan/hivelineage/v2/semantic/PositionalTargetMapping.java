package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

/** Exact ordinal mapping, including star projections. */
final class PositionalTargetMapping implements SinkMappingStrategy {
    @Override
    public MappingPlan map(BoundRelation query, SinkBindingRequest request, TargetLayout layout) {
        return SinkMappingSupport.positional(query, request, layout, false);
    }
}
