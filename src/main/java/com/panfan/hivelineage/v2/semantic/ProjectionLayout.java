package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns projection width, output definitions and hidden computations together. */
final class ProjectionLayout {
    private final OutputSchema visibleSchema;
    private final List<OutputAssignment> assignments;
    private final List<OutputSlot> hidden;
    private final Map<SlotId, BoundExpression> visibleExpressions;

    private ProjectionLayout(Builder builder) {
        boolean unknown = false;
        for (OutputSegment segment : builder.segments) {
            unknown |= segment instanceof UnknownOutputSegment;
        }
        visibleSchema = new OutputSchema(unknown ? SchemaState.UNKNOWN : SchemaState.KNOWN, builder.segments);
        assignments = Collections.unmodifiableList(new ArrayList<>(builder.assignments));
        hidden = Collections.unmodifiableList(new ArrayList<>(builder.hidden));
        visibleExpressions = Collections.unmodifiableMap(new LinkedHashMap<>(builder.visibleExpressions));
    }

    OutputSchema visibleSchema() {
        return visibleSchema;
    }

    OutputSchema operatorSchema() {
        return hidden.isEmpty() ? visibleSchema : BindingWork.combined(visibleSchema, OutputSchema.known(hidden), true);
    }

    List<OutputAssignment> assignments() {
        return assignments;
    }

    boolean hasHidden() {
        return !hidden.isEmpty();
    }

    Builder toBuilder() {
        Builder builder = new Builder();
        builder.segments.addAll(visibleSchema.getSegments());
        builder.assignments.addAll(assignments);
        builder.hidden.addAll(hidden);
        builder.visibleExpressions.putAll(visibleExpressions);
        for (OutputAssignment assignment : assignments) {
            builder.definitions.put(assignment.getOutput(), assignment.getExpression());
        }
        return builder;
    }

    ProjectionLayout visibleProjection(SourceRef source) {
        Builder visible = new Builder();
        for (OutputSegment segment : visibleSchema.getSegments()) {
            if (segment instanceof KnownOutputSegment) {
                List<Column> columns = new ArrayList<>();
                for (OutputSlot slot : ((KnownOutputSegment) segment).getSlots()) {
                    SlotId id = new SlotId(slot.getId().getValue() + "/visible-output");
                    OutputSlot output = new OutputSlot(id, slot.getDisplayName(), slot.getType(), null);
                    columns.add(new Column(output, new Expressions.SlotRef(new NodeId(id.getValue() + "/reference"),
                            slot.getType(), source, slot.getId(), Collections.<String>emptyList())));
                }
                visible.addVisibleGroup(columns);
            } else {
                visible.addUnknown((UnknownOutputSegment) segment);
            }
        }
        return visible.build();
    }

    static final class Column {
        final OutputSlot slot;
        final BoundExpression expression;

        Column(OutputSlot slot, BoundExpression expression) {
            this.slot = java.util.Objects.requireNonNull(slot, "slot");
            this.expression = java.util.Objects.requireNonNull(expression, "expression");
        }
    }

    static final class Builder {
        private final List<OutputSegment> segments = new ArrayList<>();
        private final List<OutputAssignment> assignments = new ArrayList<>();
        private final List<OutputSlot> hidden = new ArrayList<>();
        private final Map<SlotId, BoundExpression> visibleExpressions = new LinkedHashMap<>();
        private final Map<SlotId, BoundExpression> definitions = new LinkedHashMap<>();

        void addVisible(OutputSlot slot, BoundExpression expression) {
            addVisibleGroup(Collections.singletonList(new Column(slot, expression)));
        }

        void addVisibleGroup(List<Column> columns) {
            List<OutputSlot> slots = new ArrayList<>();
            Map<SlotId, BoundExpression> additions = new LinkedHashMap<>();
            for (Column column : columns) {
                if (definitions.containsKey(column.slot.getId()) || additions.put(column.slot.getId(), column.expression) != null) {
                    throw new IllegalArgumentException("Projection output is already registered");
                }
                slots.add(column.slot);
            }
            for (Column column : columns) {
                define(column.slot, column.expression);
                visibleExpressions.put(column.slot.getId(), column.expression);
            }
            segments.add(new KnownOutputSegment(slots));
        }

        void addUnknown(UnknownOutputSegment segment) {
            segments.add(java.util.Objects.requireNonNull(segment, "segment"));
        }

        SlotId addHidden(OutputSlot slot, BoundExpression expression) {
            define(slot, expression);
            hidden.add(slot);
            return slot.getId();
        }

        private void define(OutputSlot slot, BoundExpression expression) {
            java.util.Objects.requireNonNull(expression, "expression");
            if (definitions.containsKey(slot.getId())) {
                throw new IllegalArgumentException("Projection output is already registered");
            }
            definitions.put(slot.getId(), expression);
            assignments.add(new OutputAssignment(slot.getId(), expression));
        }

        Map<SlotId, BoundExpression> visibleExpressions() {
            return Collections.unmodifiableMap(visibleExpressions);
        }

        ProjectionLayout build() {
            return new ProjectionLayout(this);
        }
    }
}
