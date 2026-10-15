package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable sink order: data fields, dynamic partition fields, then static assignments. */
public final class TargetLayout {
    private final List<FieldIdentity> fields;
    private final List<FieldIdentity> dynamicPartitions;
    private final List<WriteAssignment> staticAssignments;

    private TargetLayout(List<FieldIdentity> fields, List<FieldIdentity> dynamicPartitions,
                         List<WriteAssignment> staticAssignments) {
        this.fields = ModelCollections.immutableList(fields);
        this.dynamicPartitions = ModelCollections.immutableList(dynamicPartitions);
        this.staticAssignments = ModelCollections.immutableList(staticAssignments);
    }

    public static TargetLayout from(SinkBindingRequest request) {
        List<FieldIdentity> fields = new ArrayList<>(request.getExplicitTargetFields());
        if (fields.isEmpty()) {
            for (OutputSlot slot : BindingWork.slots(request.getTargetSchema())) {
                slot.getFieldIdentity().ifPresent(fields::add);
            }
        }
        Set<FieldIdentity> partitions = new LinkedHashSet<>();
        List<FieldIdentity> dynamic = new ArrayList<>();
        List<WriteAssignment> fixed = new ArrayList<>();
        for (PartitionScope.Assignment assignment : request.getPartitionScope().getAssignments()) {
            partitions.add(assignment.getField());
            if (assignment.getKind() == PartitionScope.AssignmentKind.STATIC) {
                fixed.add(new WriteAssignment(assignment.getField(), assignment.getExpression()));
            } else {
                dynamic.add(assignment.getField());
            }
        }
        fields.removeAll(partitions);
        fields.addAll(dynamic);
        return new TargetLayout(fields, dynamic, fixed);
    }

    public List<FieldIdentity> getFields() {
        return fields;
    }

    public List<FieldIdentity> getDynamicPartitions() {
        return dynamicPartitions;
    }

    public List<WriteAssignment> getStaticAssignments() {
        return staticAssignments;
    }

    TargetLayout withFields(List<FieldIdentity> replacement) {
        return new TargetLayout(replacement, dynamicPartitions, staticAssignments);
    }
}
