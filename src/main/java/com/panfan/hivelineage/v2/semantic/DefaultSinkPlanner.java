package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Exact position binding and explicitly selected compatibility assumptions. */
public final class DefaultSinkPlanner implements SinkPlanner {
    @Override
    public SinkPlanningResult plan(BoundRelation query, SinkBindingRequest request) {
        List<WriteAssignment> assignments = new ArrayList<>();
        List<WriteAssignment> staticAssignments = new ArrayList<>();
        List<FieldIdentity> targets = new ArrayList<>(request.getExplicitTargetFields());
        if (targets.isEmpty()) {
            for (OutputSlot slot : BindingWork.slots(request.getTargetSchema())) {
                slot.getFieldIdentity().ifPresent(targets::add);
            }
        }
        Set<FieldIdentity> partitions = new LinkedHashSet<>();
        List<FieldIdentity> dynamic = new ArrayList<>();
        for (PartitionScope.Assignment partition : request.getPartitionScope().getAssignments()) {
            partitions.add(partition.getField());
            if (partition.getKind() == PartitionScope.AssignmentKind.STATIC) {
                staticAssignments.add(new WriteAssignment(partition.getField(), partition.getExpression()));
            } else {
                dynamic.add(partition.getField());
            }
        }
        targets.removeAll(partitions);
        targets.addAll(dynamic);
        List<OutputSlot> sources = BindingWork.slots(query.getOutputSchema());
        if (query.getOutputSchema().getState() == SchemaState.NOT_REQUESTED
            && request.getTargetSchema().getState() == SchemaState.NOT_REQUESTED) {
            return result(query, request, staticAssignments, MappingCoverage.NOT_REQUESTED,
                Collections.<UnknownMapping>emptyList(), DiagnosticBag.empty());
        }
        if (query.getOutputSchema().getState() != SchemaState.KNOWN) {
            for (int index = 0; index < targets.size(); index++) {
                assignments.add(unknown(query, request, targets.get(index), index, "SOURCE_WIDTH_UNKNOWN"));
            }
            assignments.addAll(staticAssignments);
            return result(query, request, assignments, MappingCoverage.UNKNOWN,
                Collections.singletonList(new UnknownMapping(request.getWriteId(), UnknownMapping.Reason.SOURCE_WIDTH_UNKNOWN,
                    "SOURCE_WIDTH_UNKNOWN")), DiagnosticBag.empty());
        }
        boolean currentApi = request.getMappingPolicy() == StarMappingPolicy.CURRENT_API_TARGET_MAPPING;
        boolean inferred = currentApi && request.getExplicitTargetFields().isEmpty()
            && request.getTargetSchema().getState() != SchemaState.KNOWN;
        boolean prefix = currentApi && request.getExplicitTargetFields().isEmpty() && !request.isSourceContainsStar()
            && request.getTargetSchema().getState() == SchemaState.KNOWN && sources.size() < targets.size();
        if (inferred) {
            targets.clear();
            int dataFields = Math.max(0, sources.size() - dynamic.size());
            for (int index = 0; index < dataFields; index++) {
                String name = SemanticNames.normalize(sources.get(index).getDisplayName());
                targets.add(new FieldIdentity(request.getTargetObject().getValue() + "/field/" + name, name));
            }
            targets.addAll(dynamic);
        } else if (prefix) {
            targets = new ArrayList<>(targets.subList(0, sources.size()));
        }
        if (targets.isEmpty()) {
            return result(query, request, staticAssignments, MappingCoverage.UNKNOWN,
                Collections.singletonList(new UnknownMapping(request.getWriteId(), UnknownMapping.Reason.TARGET_SCHEMA_UNKNOWN,
                    "TARGET_SCHEMA_UNKNOWN")), DiagnosticBag.empty());
        }
        boolean namedStar = request.isSourceContainsStar()
            && (((request.getMappingPolicy() == StarMappingPolicy.EXPLICIT_LEGACY_TARGET_MAPPING || currentApi)
                    && !request.getExplicitTargetFields().isEmpty())
                || (request.getMappingPolicy() == StarMappingPolicy.SPEC_TARGET_SCHEMA_MAPPING && request.getTargetSchema().getState() == SchemaState.KNOWN));
        if (sources.size() != targets.size() && !namedStar) {
            Diagnostic diagnostic = new Diagnostic(AnalysisStage.BINDING, "COLUMN_COUNT_MISMATCH",
                "Query has " + sources.size() + " fields but sink requires " + targets.size(), query.getSourceRef().orElse(null));
            return new SinkPlanningResult(Optional.<BoundWrite>empty(), new DiagnosticBag(Collections.singletonList(diagnostic)));
        }
        for (int index = 0; index < targets.size(); index++) {
            OutputSlot source = namedStar ? namedSource(sources, targets.get(index)) : sources.get(index);
            assignments.add(source == null ? unknown(query, request, targets.get(index), index, "LEGACY_TARGET_NAME_UNRESOLVED")
                : new WriteAssignment(targets.get(index), new Expressions.SlotRef(new NodeId(request.getWriteId().getValue() + "/source-" + index),
                    source.getType(), query.getSourceRef().orElse(null), source.getId(), Collections.<String>emptyList())));
        }
        assignments.addAll(staticAssignments);
        if (namedStar || inferred || prefix) {
            String code = request.getMappingPolicy().name();
            Diagnostic diagnostic = new Diagnostic(AnalysisStage.BINDING, code,
                "Sink fields use an explicitly selected naming, inference or prefix mapping assumption", query.getSourceRef().orElse(null));
            return result(query, request, assignments, MappingCoverage.PARTIAL,
                Collections.singletonList(new UnknownMapping(request.getWriteId(), UnknownMapping.Reason.UNSUPPORTED_RULE, code)),
                new DiagnosticBag(Collections.singletonList(diagnostic)));
        }
        return result(query, request, assignments, MappingCoverage.COMPLETE, Collections.<UnknownMapping>emptyList(), DiagnosticBag.empty());
    }

    private WriteAssignment unknown(BoundRelation query, SinkBindingRequest request, FieldIdentity field, int index, String code) {
        return new WriteAssignment(field, new Expressions.UnknownExpression(new NodeId(request.getWriteId().getValue() + "/unknown-" + index),
            TypeRef.unknown(), query.getSourceRef().orElse(null), Collections.<BoundExpression>emptyList(), code));
    }

    private OutputSlot namedSource(List<OutputSlot> sources, FieldIdentity target) {
        OutputSlot match = null;
        for (OutputSlot source : sources) {
            if (SemanticNames.normalize(source.getDisplayName()).equals(SemanticNames.normalize(target.getDisplayName()))) {
                if (match != null) {
                    return null;
                }
                match = source;
            }
        }
        return match;
    }

    private SinkPlanningResult result(BoundRelation query, SinkBindingRequest request, List<WriteAssignment> assignments,
                                      MappingCoverage coverage, List<UnknownMapping> unknown, DiagnosticBag diagnostics) {
        BoundWrite write = new BoundWrite(request.getWriteId(), request.getTargetObject(), request.getBeforeVersion(),
            request.getAfterVersion(), request.getWriteMode(), assignments, request.getPartitionScope(), null,
            coverage, unknown, query.getId());
        return new SinkPlanningResult(Optional.of(write), diagnostics);
    }
}
