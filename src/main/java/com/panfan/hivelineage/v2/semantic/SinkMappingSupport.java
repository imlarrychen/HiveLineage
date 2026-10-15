package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Assignment construction shared by policies; policy selection stays with each strategy. */
final class SinkMappingSupport {
    private SinkMappingSupport() {
    }

    static MappingPlan positional(BoundRelation query, SinkBindingRequest request, TargetLayout layout, boolean assumed) {
        List<OutputSlot> sources = BindingWork.slots(query.getOutputSchema());
        if (layout.getFields().isEmpty()) {
            return unknownTarget(request, layout);
        }
        if (sources.size() != layout.getFields().size()) {
            Diagnostic diagnostic = new Diagnostic(AnalysisStage.BINDING, "COLUMN_COUNT_MISMATCH",
                    "Query has " + sources.size() + " fields but sink requires " + layout.getFields().size(),
                    query.getSourceRef().orElse(null));
            return MappingPlan.rejected(new DiagnosticBag(Collections.singletonList(diagnostic)));
        }
        List<WriteAssignment> assignments = new ArrayList<>();
        for (int index = 0; index < layout.getFields().size(); index++) {
            assignments.add(reference(query, request, layout.getFields().get(index), sources.get(index), index));
        }
        return finish(query, request, layout, assignments, assumed);
    }

    static MappingPlan named(BoundRelation query, SinkBindingRequest request, TargetLayout layout) {
        if (layout.getFields().isEmpty()) {
            return unknownTarget(request, layout);
        }
        List<OutputSlot> sources = BindingWork.slots(query.getOutputSchema());
        List<WriteAssignment> assignments = new ArrayList<>();
        for (int index = 0; index < layout.getFields().size(); index++) {
            FieldIdentity field = layout.getFields().get(index);
            OutputSlot source = namedSource(sources, field);
            assignments.add(source == null ? unknown(query, request, field, index, "LEGACY_TARGET_NAME_UNRESOLVED")
                    : reference(query, request, field, source, index));
        }
        return finish(query, request, layout, assignments, true);
    }

    static MappingPlan unknownSource(BoundRelation query, SinkBindingRequest request, TargetLayout layout) {
        List<WriteAssignment> assignments = new ArrayList<>();
        for (int index = 0; index < layout.getFields().size(); index++) {
            assignments.add(unknown(query, request, layout.getFields().get(index), index, "SOURCE_WIDTH_UNKNOWN"));
        }
        assignments.addAll(layout.getStaticAssignments());
        return MappingPlan.mapped(assignments, MappingCoverage.UNKNOWN, Collections.singletonList(
                new UnknownMapping(request.getWriteId(), UnknownMapping.Reason.SOURCE_WIDTH_UNKNOWN, "SOURCE_WIDTH_UNKNOWN")),
                DiagnosticBag.empty());
    }

    private static MappingPlan unknownTarget(SinkBindingRequest request, TargetLayout layout) {
        return MappingPlan.mapped(layout.getStaticAssignments(), MappingCoverage.UNKNOWN, Collections.singletonList(
                new UnknownMapping(request.getWriteId(), UnknownMapping.Reason.TARGET_SCHEMA_UNKNOWN, "TARGET_SCHEMA_UNKNOWN")),
                DiagnosticBag.empty());
    }

    private static MappingPlan finish(BoundRelation query, SinkBindingRequest request, TargetLayout layout,
                                      List<WriteAssignment> assignments, boolean assumed) {
        assignments.addAll(layout.getStaticAssignments());
        if (!assumed) {
            return MappingPlan.complete(assignments, MappingCoverage.COMPLETE);
        }
        String code = request.getMappingPolicy().name();
        Diagnostic diagnostic = new Diagnostic(AnalysisStage.BINDING, code,
                "Sink fields use an explicitly selected naming, inference or prefix mapping assumption", query.getSourceRef().orElse(null));
        return MappingPlan.mapped(assignments, MappingCoverage.PARTIAL, Collections.singletonList(
                new UnknownMapping(request.getWriteId(), UnknownMapping.Reason.UNSUPPORTED_RULE, code)),
                new DiagnosticBag(Collections.singletonList(diagnostic)));
    }

    private static WriteAssignment reference(BoundRelation query, SinkBindingRequest request,
                                              FieldIdentity field, OutputSlot source, int index) {
        return new WriteAssignment(field, new Expressions.SlotRef(new NodeId(request.getWriteId().getValue() + "/source-" + index),
                source.getType(), query.getSourceRef().orElse(null), source.getId(), Collections.<String>emptyList()));
    }

    private static WriteAssignment unknown(BoundRelation query, SinkBindingRequest request, FieldIdentity field, int index, String code) {
        return new WriteAssignment(field, new Expressions.UnknownExpression(new NodeId(request.getWriteId().getValue() + "/unknown-" + index),
                TypeRef.unknown(), query.getSourceRef().orElse(null), Collections.<BoundExpression>emptyList(), code));
    }

    private static OutputSlot namedSource(List<OutputSlot> sources, FieldIdentity target) {
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
}
