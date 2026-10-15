package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Assignments and their evidence are one immutable strategy result. */
public final class MappingPlan {
    private final boolean accepted;
    private final List<WriteAssignment> assignments;
    private final MappingCoverage coverage;
    private final List<UnknownMapping> unknownMappings;
    private final DiagnosticBag diagnostics;

    private MappingPlan(boolean accepted, List<WriteAssignment> assignments, MappingCoverage coverage,
                        List<UnknownMapping> unknownMappings, DiagnosticBag diagnostics) {
        this.accepted = accepted;
        this.assignments = ModelCollections.immutableList(assignments);
        this.coverage = Objects.requireNonNull(coverage, "coverage");
        this.unknownMappings = ModelCollections.immutableList(unknownMappings);
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public static MappingPlan complete(List<WriteAssignment> assignments, MappingCoverage coverage) {
        return mapped(assignments, coverage, Collections.<UnknownMapping>emptyList(), DiagnosticBag.empty());
    }

    public static MappingPlan mapped(List<WriteAssignment> assignments, MappingCoverage coverage,
                                     List<UnknownMapping> unknownMappings, DiagnosticBag diagnostics) {
        return new MappingPlan(true, assignments, coverage, unknownMappings, diagnostics);
    }

    public static MappingPlan rejected(DiagnosticBag diagnostics) {
        return new MappingPlan(false, Collections.<WriteAssignment>emptyList(), MappingCoverage.UNKNOWN,
                Collections.<UnknownMapping>emptyList(), diagnostics);
    }

    public boolean isAccepted() {
        return accepted;
    }

    public List<WriteAssignment> getAssignments() {
        return assignments;
    }

    public MappingCoverage getCoverage() {
        return coverage;
    }

    public List<UnknownMapping> getUnknownMappings() {
        return unknownMappings;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    SinkPlanningResult toResult(BoundRelation query, SinkBindingRequest request) {
        if (!accepted) {
            return new SinkPlanningResult(Optional.<BoundWrite>empty(), diagnostics);
        }
        BoundWrite write = new BoundWrite(request.getWriteId(), request.getTargetObject(), request.getBeforeVersion(),
                request.getAfterVersion(), request.getWriteMode(), assignments, request.getPartitionScope(), null,
                coverage, unknownMappings, query.getId());
        return new SinkPlanningResult(Optional.of(write), diagnostics);
    }
}
