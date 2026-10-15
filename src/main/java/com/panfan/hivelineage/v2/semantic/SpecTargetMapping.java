package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

/** A star can use names only against a known target schema. */
final class SpecTargetMapping implements SinkMappingStrategy {
    @Override
    public MappingPlan map(BoundRelation query, SinkBindingRequest request, TargetLayout layout) {
        if (request.isSourceContainsStar() && request.getTargetSchema().getState() == SchemaState.KNOWN) {
            return SinkMappingSupport.named(query, request, layout);
        }
        return SinkMappingSupport.positional(query, request, layout, false);
    }
}
