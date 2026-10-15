package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.relation.BoundRelation;

/** Maps a known-width query to a complete assignment/quality proposal, including static partitions. */
@FunctionalInterface
public interface SinkMappingStrategy {
    MappingPlan map(BoundRelation query, SinkBindingRequest request, TargetLayout layout);
}
