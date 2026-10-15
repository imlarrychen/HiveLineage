package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

/**
 * Produces assignment structure before lineage propagation; no implementation yet.
 */
public interface SinkPlanner {
    SinkPlanningResult plan(BoundRelation query, SinkBindingRequest request);
}
