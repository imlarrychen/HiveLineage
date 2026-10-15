package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.SlotProvenanceIndex;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Collects bound function usage, including window clauses and subquery result provenance. */
public final class FunctionUsageCollector {
    private final ExpressionShapeAnalyzer shapes;
    private final Map<SlotId, BoundExpression> assignments;
    private final Map<SlotId, SlotId> mappings;
    private final Map<SlotId, Relations.SetOutputMapping> sets;
    private final Map<SlotId, String> setNames;
    private final Map<SlotId, Relations.Generate> generators;
    private final Map<SlotId, Relations.WindowComputation> windows;

    public FunctionUsageCollector(SlotProvenanceIndex provenance, ExpressionShapeAnalyzer shapes) {
        this.shapes = shapes;
        this.assignments = provenance.assignments();
        this.mappings = provenance.mappings();
        this.sets = provenance.sets();
        this.setNames = provenance.setNames();
        this.generators = provenance.generators();
        this.windows = provenance.windows();
    }

    public List<String> functions(BoundExpression expression) {
        Set<String> functions = new LinkedHashSet<String>();
        collectFunctions(expression, functions, new LinkedHashSet<SlotId>());
        return new ArrayList<String>(functions);
    }

    public List<String> functionsWithOccurrences(BoundExpression expression) {
        List<String> functions = new ArrayList<String>();
        collectFunctions(expression, functions, new LinkedHashSet<SlotId>());
        return functions;
    }

    private void collectFunctions(BoundExpression expression, Collection<String> functions, Set<SlotId> seen) {
        if (expression instanceof Expressions.SlotRef) {
            SlotId id = ((Expressions.SlotRef) expression).getSlotId();
            collectSlotFunctions(id, functions, seen);
        } else if (expression instanceof Expressions.ScalarSubquery) {
            collectSlotFunctions(((Expressions.ScalarSubquery) expression).getResultSlot(), functions, seen);
        } else if (expression instanceof Expressions.Call) {
            Expressions.Call call = (Expressions.Call) expression;
            if (call.getFunction().getKind() != FunctionSymbol.FunctionKind.OPERATOR) {
                functions.add(call.getFunction().getName().getDisplayName());
            }
        } else if (expression instanceof Expressions.Cast) {
            functions.add("cast");
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            collectFunctions(child, functions, seen);
        }
    }

    private void collectSlotFunctions(SlotId slot, Collection<String> functions, Set<SlotId> seen) {
        if (!seen.add(slot) || shapes.boundary(slot)) {
            return;
        }
        if (windows.containsKey(slot)) {
            Relations.WindowComputation window = windows.get(slot);
            collectFunctions(window.getAssignment().getExpression(), functions, seen);
            for (BoundExpression partition : window.getPartitions()) {
                collectFunctions(partition, functions, seen);
            }
            for (Relations.OrderKey key : window.getOrderKeys()) {
                collectFunctions(key.getExpression(), functions, seen);
            }
            window.getFrame().ifPresent(frame -> {
                frame.getStart().getOffset().ifPresent(offset -> collectFunctions(offset, functions, seen));
                frame.getEnd().getOffset().ifPresent(offset -> collectFunctions(offset, functions, seen));
            });
        } else if (assignments.containsKey(slot)) {
            collectFunctions(assignments.get(slot), functions, seen);
        } else if (mappings.containsKey(slot)) {
            collectSlotFunctions(mappings.get(slot), functions, seen);
        } else if (sets.containsKey(slot)) {
            if (!shapes.literalSlot(slot, new LinkedHashSet<SlotId>())) {
                functions.add(setNames.get(slot));
            }
            for (Relations.BranchSlot branch : sets.get(slot).getBranchSlots()) {
                if (branch.getSlot().isPresent()) {
                    collectSlotFunctions(branch.getSlot().get(), functions, seen);
                }
            }
        } else if (generators.containsKey(slot)) {
            Relations.Generate generate = generators.get(slot);
            functions.add(generate.getFunction().getName().getDisplayName());
            for (BoundExpression argument : generate.getArguments()) {
                collectFunctions(argument, functions, seen);
            }
        }
    }

}
