package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.export.BoundInputObjectProjector;
import com.panfan.hivelineage.v2.export.BoundNameIndex;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Chooses display boundaries over proven dependencies, without producing or changing lineage facts. */
final class LegacyColumnSourceSelector {
    private final BoundAnalysisUnit unit;
    private final BoundNameIndex names;
    private final boolean includeTemporary;
    private final Map<SlotId, BoundExpression> assignments = new LinkedHashMap<SlotId, BoundExpression>();
    private final Map<SlotId, SlotId> mappings = new LinkedHashMap<SlotId, SlotId>();
    private final Map<SlotId, List<SlotId>> sets = new LinkedHashMap<SlotId, List<SlotId>>();
    private final Map<SlotId, List<BoundExpression>> extra = new LinkedHashMap<SlotId, List<BoundExpression>>();

    LegacyColumnSourceSelector(BoundAnalysisUnit unit, BoundNameIndex names, boolean includeTemporary) {
        this.unit = unit;
        this.names = names;
        this.includeTemporary = includeTemporary;
        for (BoundRelation relation : unit.getRelations()) {
            register(relation);
        }
    }

    List<Dependency> select(List<Dependency> dependencies, BoundExpression expression, BoundWrite write) {
        return select(dependencies, expression, write.getSourceRelation());
    }

    List<Dependency> select(List<Dependency> dependencies, BoundExpression expression, NodeId root) {
        return select(dependencies, expression, Optional.of(root));
    }

    private List<Dependency> select(List<Dependency> dependencies, BoundExpression expression, Optional<NodeId> root) {
        boolean boundary = dependencies.stream().anyMatch(dependency -> names.endpointObject(dependency.getSource())
                .map(this::boundary).orElse(false));
        if (!boundary) {
            return dependencies;
        }
        List<ColumnEndpoint> direct = new ArrayList<ColumnEndpoint>();
        expression(expression, direct, new LinkedHashSet<SlotId>());
        Set<ObjectId> rowInputs = directObjects(root);
        List<Dependency> selected = new ArrayList<Dependency>();
        for (Dependency dependency : dependencies) {
            Optional<ObjectId> source = names.endpointObject(dependency.getSource());
            if (!source.isPresent()) {
                selected.add(dependency);
            } else if (dependency.getRole() == DependencyRole.ROWSET && rowInputs.contains(source.get())) {
                selected.add(dependency);
            } else if (dependency.getSource() instanceof ColumnEndpoint
                    && direct.stream().anyMatch(value -> matches(value, (ColumnEndpoint) dependency.getSource()))) {
                selected.add(dependency);
            }
        }
        return selected;
    }

    private Set<ObjectId> directObjects(Optional<NodeId> root) {
        BoundInputObjectProjector projector = new BoundInputObjectProjector(unit, names, includeTemporary);
        List<com.panfan.hivelineage.v2.common.QualifiedName> inputs = root.map(projector::inputsForRoot).orElse(Collections.emptyList());
        Set<ObjectId> objects = new LinkedHashSet<ObjectId>();
        unit.getObjects().forEach((id, object) -> {
            if (inputs.contains(object.getName())) {
                objects.add(id);
            }
        });
        return objects;
    }

    private boolean boundary(ObjectId object) {
        return names.view(object) || includeTemporary && names.temporary(object);
    }

    private static boolean matches(ColumnEndpoint direct, ColumnEndpoint dependency) {
        List<String> prefix = direct.getFieldPath();
        List<String> path = dependency.getFieldPath();
        return direct.getObjectId().equals(dependency.getObjectId()) && direct.getFieldId().equals(dependency.getFieldId())
                && path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
    }

    private void expression(BoundExpression expression, List<ColumnEndpoint> direct, Set<SlotId> seen) {
        if (expression instanceof Expressions.SlotRef) {
            slot(((Expressions.SlotRef) expression).getSlotId(), direct, seen);
        } else if (expression instanceof Expressions.CorrelatedSlotRef) {
            slot(((Expressions.CorrelatedSlotRef) expression).getSlotId(), direct, seen);
        } else if (expression instanceof QualifiedFieldHandle) {
            QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
            names.ir().object(field.getOwner()).ifPresent(object -> direct.add(
                    new ColumnEndpoint(object, field.getFieldIdentity().getId(), field.getFieldPath())));
        } else if (expression instanceof CorrelatedQualifiedFieldHandle) {
            CorrelatedQualifiedFieldHandle field = (CorrelatedQualifiedFieldHandle) expression;
            names.ir().object(field.getOwner()).ifPresent(object -> direct.add(
                    new ColumnEndpoint(object, field.getFieldIdentity().getId(), field.getFieldPath())));
        } else if (expression instanceof Expressions.ScalarSubquery) {
            slot(((Expressions.ScalarSubquery) expression).getResultSlot(), direct, seen);
        } else if (expression instanceof Expressions.InSubquery) {
            slot(((Expressions.InSubquery) expression).getResultSlot(), direct, seen);
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            expression(child, direct, seen);
        }
    }

    private void slot(SlotId slot, List<ColumnEndpoint> direct, Set<SlotId> seen) {
        if (!seen.add(slot)) {
            return;
        }
        Optional<BoundRelation> owner = names.ir().slotOwner(slot);
        if (owner.isPresent() && owner.get() instanceof Relations.DefinitionInstance
                && ((Relations.DefinitionInstance) owner.get()).getPhysicalObject().map(this::boundary).orElse(false)) {
            ObjectId object = ((Relations.DefinitionInstance) owner.get()).getPhysicalObject().get();
            names.ir().slot(slot).flatMap(OutputSlot::getFieldIdentity).ifPresent(field -> direct.add(
                    new ColumnEndpoint(object, field.getId(), Collections.emptyList())));
            return;
        }
        if (assignments.containsKey(slot)) {
            expression(assignments.get(slot), direct, seen);
        } else if (mappings.containsKey(slot)) {
            slot(mappings.get(slot), direct, seen);
        } else if (sets.containsKey(slot)) {
            for (SlotId branch : sets.get(slot)) {
                slot(branch, direct, seen);
            }
        } else if (owner.isPresent() && owner.get() instanceof Relations.Scan) {
            Optional<OutputSlot> output = names.ir().slot(slot);
            if (output.isPresent()) {
                output.get().getFieldIdentity().ifPresent(field -> direct.add(new ColumnEndpoint(
                        ((Relations.Scan) owner.get()).getObjectId(), field.getId(), Collections.emptyList())));
            }
        }
        for (BoundExpression expression : extra.getOrDefault(slot, Collections.emptyList())) {
            expression(expression, direct, seen);
        }
    }

    private void register(BoundRelation relation) {
        if (relation instanceof Relations.Project) {
            assignments(((Relations.Project) relation).getAssignments());
        } else if (relation instanceof Relations.Aggregate) {
            assignments(((Relations.Aggregate) relation).getAssignments());
        } else if (relation instanceof Relations.Window) {
            Relations.Window window = (Relations.Window) relation;
            mappings(window.getRetainedSlots());
            for (Relations.WindowComputation computation : window.getComputations()) {
                assignments.put(computation.getAssignment().getOutput(), computation.getAssignment().getExpression());
                List<BoundExpression> inputs = new ArrayList<BoundExpression>(computation.getPartitions());
                computation.getOrderKeys().forEach(key -> inputs.add(key.getExpression()));
                extra.put(computation.getAssignment().getOutput(), inputs);
            }
        } else if (relation instanceof Relations.SetOp) {
            for (Relations.SetOutputMapping mapping : ((Relations.SetOp) relation).getMappings()) {
                List<SlotId> branches = new ArrayList<SlotId>();
                mapping.getBranchSlots().forEach(branch -> branch.getSlot().ifPresent(branches::add));
                sets.put(mapping.getOutput(), branches);
            }
        } else if (relation instanceof Relations.Filter) {
            mappings(((Relations.Filter) relation).getMappings());
        } else if (relation instanceof Relations.Join) {
            mappings(((Relations.Join) relation).getMappings());
        } else if (relation instanceof Relations.DefinitionInstance) {
            mappings(((Relations.DefinitionInstance) relation).getMappings());
        } else if (relation instanceof Relations.Sort) {
            mappings(((Relations.Sort) relation).getMappings());
        } else if (relation instanceof Relations.Limit) {
            mappings(((Relations.Limit) relation).getMappings());
        } else if (relation instanceof Relations.Distinct) {
            mappings(((Relations.Distinct) relation).getMappings());
        } else if (relation instanceof Relations.Generate) {
            Relations.Generate generate = (Relations.Generate) relation;
            mappings(generate.getRetainedSlots());
            for (SlotId slot : generate.getGeneratedSlots()) {
                extra.put(slot, generate.getArguments());
            }
        }
    }

    private void assignments(List<OutputAssignment> values) {
        values.forEach(assignment -> assignments.put(assignment.getOutput(), assignment.getExpression()));
    }

    private void mappings(List<SlotMapping> values) {
        values.forEach(mapping -> {
            if (!mapping.getOutput().equals(mapping.getInput())) {
                mappings.put(mapping.getOutput(), mapping.getInput());
            }
        });
    }
}
