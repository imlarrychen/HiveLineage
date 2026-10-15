package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

import java.util.Collections;
import java.util.Map;

/** Selects a mapping contract after computing the shared partition layout once. */
public final class DefaultSinkPlanner implements SinkPlanner {
    private final Map<StarMappingPolicy, SinkMappingStrategy> strategies;

    public DefaultSinkPlanner() {
        this(Collections.<StarMappingPolicy, SinkMappingStrategy>emptyMap());
    }

    /** Registrations replace individual policies without changing planning or other policies. */
    public DefaultSinkPlanner(Map<StarMappingPolicy, SinkMappingStrategy> registrations) {
        strategies = SinkMappingStrategies.configured(registrations);
    }

    @Override
    public SinkPlanningResult plan(BoundRelation query, SinkBindingRequest request) {
        TargetLayout layout = TargetLayout.from(request);
        MappingPlan mapping;
        if (query.getOutputSchema().getState() == SchemaState.NOT_REQUESTED
                && request.getTargetSchema().getState() == SchemaState.NOT_REQUESTED) {
            mapping = MappingPlan.complete(layout.getStaticAssignments(), MappingCoverage.NOT_REQUESTED);
        } else if (query.getOutputSchema().getState() != SchemaState.KNOWN) {
            mapping = SinkMappingSupport.unknownSource(query, request, layout);
        } else {
            mapping = strategies.get(request.getMappingPolicy()).map(query, request, layout);
        }
        return mapping.toResult(query, request);
    }
}
