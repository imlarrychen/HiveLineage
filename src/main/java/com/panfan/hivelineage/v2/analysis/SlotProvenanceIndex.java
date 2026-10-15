package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable structural provenance shared by display and compatibility projections of one unit.
 */
public final class SlotProvenanceIndex {
    private final Map<SlotId, BoundExpression> assignments = new LinkedHashMap<SlotId, BoundExpression>();
    private final Map<SlotId, SlotId> mappings = new LinkedHashMap<SlotId, SlotId>();
    private final Map<SlotId, Relations.SetOutputMapping> sets = new LinkedHashMap<SlotId, Relations.SetOutputMapping>();
    private final Map<SlotId, String> setNames = new LinkedHashMap<SlotId, String>();
    private final Map<SlotId, Relations.Generate> generators = new LinkedHashMap<SlotId, Relations.Generate>();
    private final Map<SlotId, Relations.WindowComputation> windows = new LinkedHashMap<SlotId, Relations.WindowComputation>();

    private final Map<SlotId, List<BoundExpression>> extra = new LinkedHashMap<SlotId, List<BoundExpression>>();

    public SlotProvenanceIndex(BoundAnalysisUnit unit) {
        for (BoundRelation relation : unit.getRelations()) {
            register(relation);
        }
    }

    private void register(BoundRelation relation) {
        if (relation instanceof Relations.Project) {
            assignments(((Relations.Project) relation).getAssignments());
        } else if (relation instanceof Relations.Aggregate) {
            assignments(((Relations.Aggregate) relation).getAssignments());
        } else if (relation instanceof Relations.Window) {
            for (Relations.WindowComputation computation : ((Relations.Window) relation).getComputations()) {
                assignments.put(computation.getAssignment().getOutput(), computation.getAssignment().getExpression());
                windows.put(computation.getAssignment().getOutput(), computation);
                List<BoundExpression> inputs = new ArrayList<BoundExpression>(computation.getPartitions());
                computation.getOrderKeys().forEach(key -> inputs.add(key.getExpression()));
                computation.getFrame().ifPresent(frame -> {
                    frame.getStart().getOffset().ifPresent(inputs::add);
                    frame.getEnd().getOffset().ifPresent(inputs::add);
                });
                extra.put(computation.getAssignment().getOutput(), Collections.unmodifiableList(inputs));
            }
            mappings(((Relations.Window) relation).getRetainedSlots());
        } else if (relation instanceof Relations.SetOp) {
            Relations.SetOp set = (Relations.SetOp) relation;
            String operation = set.getOperator().name().toLowerCase(java.util.Locale.ROOT);
            if (set.getOperator() == Relations.SetOperator.UNION) {
                operation += set.getDuplicatePolicy() == Relations.DuplicatePolicy.ALL ? "_all" : "_distinct";
            } else if (set.getDuplicatePolicy() == Relations.DuplicatePolicy.ALL) {
                operation += "_all";
            }
            for (Relations.SetOutputMapping mapping : set.getMappings()) {
                sets.put(mapping.getOutput(), mapping);
                setNames.put(mapping.getOutput(), operation);
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
                generators.put(slot, generate);
                extra.put(slot, generate.getArguments());
            }
        }
    }

    private void assignments(List<OutputAssignment> values) {
        for (OutputAssignment assignment : values) {
            assignments.put(assignment.getOutput(), assignment.getExpression());
        }
    }

    private void mappings(List<SlotMapping> values) {
        for (SlotMapping mapping : values) {
            if (!mapping.getOutput().equals(mapping.getInput())) {
                mappings.put(mapping.getOutput(), mapping.getInput());
            }
        }
    }

    public Map<SlotId, BoundExpression> assignments() {
        return Collections.unmodifiableMap(assignments);
    }

    public Map<SlotId, SlotId> mappings() {
        return Collections.unmodifiableMap(mappings);
    }

    public Map<SlotId, Relations.SetOutputMapping> sets() {
        return Collections.unmodifiableMap(sets);
    }

    public Map<SlotId, String> setNames() {
        return Collections.unmodifiableMap(setNames);
    }

    public Map<SlotId, Relations.Generate> generators() {
        return Collections.unmodifiableMap(generators);
    }

    public Map<SlotId, Relations.WindowComputation> windows() {
        return Collections.unmodifiableMap(windows);
    }

    public Map<SlotId, List<BoundExpression>> extra() {
        return Collections.unmodifiableMap(extra);
    }

}
