package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.SlotProvenanceIndex;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Classifies expression structure and provenance without rendering SQL or display strings. */
public final class ExpressionShapeAnalyzer {
    private final BoundNameIndex names;
    private final Predicate<ObjectId> displayBoundary;
    private final Map<SlotId, BoundExpression> assignments;
    private final Map<SlotId, SlotId> mappings;
    private final Map<SlotId, Relations.SetOutputMapping> sets;
    private final Map<SlotId, Relations.Generate> generators;

    public ExpressionShapeAnalyzer(BoundAnalysisUnit unit, BoundNameIndex names, Predicate<ObjectId> displayBoundary) {
        this(new SlotProvenanceIndex(unit), names, displayBoundary);
    }

    public ExpressionShapeAnalyzer(SlotProvenanceIndex provenance, BoundNameIndex names, Predicate<ObjectId> displayBoundary) {
        this.names = names;
        this.displayBoundary = displayBoundary;
        this.assignments = provenance.assignments();
        this.mappings = provenance.mappings();
        this.sets = provenance.sets();
        this.generators = provenance.generators();
    }

    boolean setSlot(SlotId slot) {
        return setSlot(slot, new LinkedHashSet<SlotId>());
    }

    boolean generatedSlot(SlotId slot) {
        return generatedSlot(slot, new LinkedHashSet<SlotId>());
    }

    public BoundExpression origin(BoundExpression expression) {
        if (!(expression instanceof Expressions.SlotRef)) {
            return expression;
        }
        SlotId slot = ((Expressions.SlotRef) expression).getSlotId();
        Set<SlotId> seen = new LinkedHashSet<SlotId>();
        while (seen.add(slot)) {
            if (boundary(slot)) {
                break;
            }
            if (assignments.containsKey(slot)) {
                BoundExpression assigned = assignments.get(slot);
                if (!(assigned instanceof Expressions.SlotRef)) {
                    return assigned;
                }
                SlotId parent = ((Expressions.SlotRef) assigned).getSlotId();
                if (!assignments.containsKey(parent) && !mappings.containsKey(parent)) {
                    return assigned;
                }
                slot = parent;
            } else if (mappings.containsKey(slot)) {
                slot = mappings.get(slot);
            } else {
                break;
            }
        }
        return expression;
    }

    private boolean setSlot(SlotId slot, Set<SlotId> seen) {
        if (!seen.add(slot)) {
            return false;
        }
        if (sets.containsKey(slot)) {
            return true;
        }
        if (mappings.containsKey(slot)) {
            return setSlot(mappings.get(slot), seen);
        }
        BoundExpression assignment = assignments.get(slot);
        return assignment instanceof Expressions.SlotRef && setSlot(((Expressions.SlotRef) assignment).getSlotId(), seen);
    }

    private boolean generatedSlot(SlotId slot, Set<SlotId> seen) {
        if (!seen.add(slot)) {
            return false;
        }
        if (generators.containsKey(slot)) {
            return true;
        }
        if (mappings.containsKey(slot)) {
            return generatedSlot(mappings.get(slot), seen);
        }
        BoundExpression expression = assignments.get(slot);
        return expression instanceof Expressions.SlotRef && generatedSlot(((Expressions.SlotRef) expression).getSlotId(), seen);
    }

    public boolean hasSetProvenance(BoundExpression expression) {
        if (expression instanceof Expressions.SlotRef
                && setSlot(((Expressions.SlotRef) expression).getSlotId(), new LinkedHashSet<SlotId>())) {
            return true;
        }
        for (BoundExpression child : IrTraversal.children(expression)) {
            if (hasSetProvenance(child)) {
                return true;
            }
        }
        return false;
    }

    public boolean complex(BoundExpression expression) {
        BoundExpression origin = origin(expression);
        boolean set = hasSetProvenance(expression);
        boolean generated = origin instanceof Expressions.SlotRef
                && generatedSlot(((Expressions.SlotRef) origin).getSlotId(), new LinkedHashSet<SlotId>());
        return set || generated || !(origin instanceof Expressions.SlotRef) && !(origin instanceof QualifiedFieldHandle)
                && !(origin instanceof Expressions.Literal);
    }

    /**
     * Only literal leaves and sets of literal leaves are constant assignments; calls are never folded here.
     */
    public boolean literalOnly(BoundExpression expression) {
        return literalOnly(expression, new LinkedHashSet<SlotId>());
    }

    private boolean literalOnly(BoundExpression expression, Set<SlotId> seen) {
        if (expression instanceof Expressions.Literal) {
            return true;
        }
        return expression instanceof Expressions.SlotRef && ((Expressions.SlotRef) expression).getFieldPath().isEmpty()
                && literalSlot(((Expressions.SlotRef) expression).getSlotId(), seen);
    }

    boolean literalSlot(SlotId slot, Set<SlotId> seen) {
        if (!seen.add(slot) || boundary(slot)) {
            return false;
        }
        if (assignments.containsKey(slot)) {
            return literalOnly(assignments.get(slot), seen);
        }
        if (mappings.containsKey(slot)) {
            return literalSlot(mappings.get(slot), seen);
        }
        if (sets.containsKey(slot)) {
            List<Relations.BranchSlot> branches = sets.get(slot).getBranchSlots();
            if (branches.isEmpty()) {
                return false;
            }
            for (Relations.BranchSlot branch : branches) {
                if (!branch.getSlot().isPresent() || !literalSlot(branch.getSlot().get(), new LinkedHashSet<SlotId>(seen))) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    boolean boundary(SlotId slot) {
        return names.ir().slotOwner(slot).filter(owner -> owner instanceof Relations.DefinitionInstance)
                .map(owner -> ((Relations.DefinitionInstance) owner).getPhysicalObject().map(displayBoundary::test).orElse(false))
                .orElse(false);
    }

}
