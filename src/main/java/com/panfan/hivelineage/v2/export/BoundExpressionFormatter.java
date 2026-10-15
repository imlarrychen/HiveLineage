package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.SlotProvenanceIndex;
import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Text projection over bound structure; rendering never changes a dependency. */
public final class BoundExpressionFormatter {
    private final SourceRenderer renderer;
    private final BoundNameIndex names;
    private final Predicate<ObjectId> displayBoundary;
    private final Map<SlotId, BoundExpression> assignments;
    private final Map<SlotId, SlotId> mappings;
    private final Map<SlotId, Relations.SetOutputMapping> sets;
    private final Map<SlotId, String> setNames;
    private final Map<SlotId, Relations.Generate> generators;
    private final Map<SlotId, Relations.WindowComputation> windows;

    public BoundExpressionFormatter(BoundAnalysisUnit unit, BoundNameIndex names, SourceRenderer renderer) {
        this(unit, names, renderer, object -> false);
    }

    public BoundExpressionFormatter(BoundAnalysisUnit unit, BoundNameIndex names, SourceRenderer renderer,
                                    Predicate<ObjectId> displayBoundary) {
        this(new SlotProvenanceIndex(unit), names, renderer, displayBoundary);
    }

    public BoundExpressionFormatter(SlotProvenanceIndex provenance, BoundNameIndex names, SourceRenderer renderer,
                                    Predicate<ObjectId> displayBoundary) {
        this.renderer = renderer;
        this.names = names;
        this.displayBoundary = displayBoundary;
        this.assignments = provenance.assignments();
        this.mappings = provenance.mappings();
        this.sets = provenance.sets();
        this.setNames = provenance.setNames();
        this.generators = provenance.generators();
        this.windows = provenance.windows();
    }

    public Optional<String> format(BoundExpression expression) {
        if (expression instanceof Expressions.SlotRef) {
            return slot(((Expressions.SlotRef) expression).getSlotId(), false, new LinkedHashSet<SlotId>());
        }
        return expression(expression, false, new LinkedHashSet<SlotId>());
    }

    /** A synthesized display string does not establish that expression text was extracted. */
    public boolean extracted(BoundExpression expression) {
        if (expression instanceof Expressions.SlotRef) {
            return extractedSlot(((Expressions.SlotRef) expression).getSlotId(), new LinkedHashSet<SlotId>());
        }
        return rendered(expression).isPresent();
    }

    private boolean extractedSlot(SlotId slot, Set<SlotId> seen) {
        if (!seen.add(slot)) {
            return false;
        }
        if (boundary(slot)) {
            return false;
        }
        if (sets.containsKey(slot)) {
            for (Relations.BranchSlot branch : sets.get(slot).getBranchSlots()) {
                if (!branch.getSlot().isPresent() || !extractedSlot(branch.getSlot().get(), new LinkedHashSet<SlotId>(seen))) {
                    return false;
                }
            }
            return !sets.get(slot).getBranchSlots().isEmpty();
        }
        if (generators.containsKey(slot)) {
            Relations.Generate generate = generators.get(slot);
            for (BoundExpression argument : generate.getArguments()) {
                if (!extracted(argument)) {
                    return false;
                }
            }
            return generate.getSourceRef().flatMap(renderer::render).map(String::trim).filter(value -> !value.isEmpty()).isPresent();
        }
        if (assignments.containsKey(slot)) {
            BoundExpression expression = assignments.get(slot);
            if (expression instanceof Expressions.SlotRef) {
                Expressions.SlotRef reference = (Expressions.SlotRef) expression;
                boolean definition = names.ir().slotOwner(reference.getSlotId())
                        .map(owner -> owner instanceof Relations.DefinitionInstance).orElse(false);
                boolean inherited = !boundary(reference.getSlotId())
                        && (setSlot(reference.getSlotId(), new LinkedHashSet<SlotId>()) || definition);
                return inherited ? extractedSlot(reference.getSlotId(), seen) : rendered(expression).isPresent();
            }
            return rendered(expression).isPresent();
        }
        return mappings.containsKey(slot) && extractedSlot(mappings.get(slot), seen);
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

    private Optional<String> slot(SlotId slot, boolean physical, Set<SlotId> seen) {
        if (!seen.add(slot)) {
            return Optional.empty();
        }
        if (!physical && boundary(slot)) {
            Optional<BoundRelation> owner = names.ir().slotOwner(slot);
            Optional<OutputSlot> output = names.ir().slot(slot);
            if (owner.isPresent() && output.isPresent() && output.get().getFieldIdentity().isPresent()) {
                return ((Relations.DefinitionInstance) owner.get()).getPhysicalObject().flatMap(object -> names.column(
                        new ColumnEndpoint(object, output.get().getFieldIdentity().get().getId(), Collections.emptyList()), false));
            }
            return Optional.empty();
        }
        if (generators.containsKey(slot)) {
            Relations.Generate generate = generators.get(slot);
            List<String> arguments = new ArrayList<String>();
            for (BoundExpression argument : generate.getArguments()) {
                arguments.add(expression(argument, physical, new LinkedHashSet<SlotId>(seen)).orElse("?"));
            }
            return Optional.of(generate.getFunction().getName().getDisplayName() + "(" + String.join(", ", arguments) + ")");
        }
        if (sets.containsKey(slot)) {
            List<String> branches = new ArrayList<String>();
            for (Relations.BranchSlot branch : sets.get(slot).getBranchSlots()) {
                if (!branch.getSlot().isPresent()) {
                    return Optional.empty();
                }
                Optional<String> value = slot(branch.getSlot().get(), true, new LinkedHashSet<SlotId>(seen));
                if (!value.isPresent()) {
                    return Optional.empty();
                }
                branches.add(value.get());
            }
            return Optional.of(setNames.get(slot) + "(" + String.join(", ", branches) + ")");
        }
        if (assignments.containsKey(slot)) {
            return expression(assignments.get(slot), physical, seen);
        }
        if (mappings.containsKey(slot)) {
            return slot(mappings.get(slot), physical, seen);
        }
        Optional<BoundRelation> owner = names.ir().slotOwner(slot);
        Optional<OutputSlot> declaration = names.ir().slot(slot);
        if (owner.isPresent() && owner.get() instanceof Relations.Scan && declaration.isPresent()
                && declaration.get().getFieldIdentity().isPresent()) {
            return names.column(new ColumnEndpoint(((Relations.Scan) owner.get()).getObjectId(),
                    declaration.get().getFieldIdentity().get().getId(), Collections.emptyList()), false);
        }
        return Optional.empty();
    }

    private Optional<String> expression(BoundExpression expression, boolean physical, Set<SlotId> seen) {
        if (expression instanceof Expressions.Literal) {
            return Optional.of("constant(" + literal((Expressions.Literal) expression) + ")");
        }
        if (expression instanceof Expressions.SlotRef) {
            SlotId slot = ((Expressions.SlotRef) expression).getSlotId();
            Optional<String> inherited = slot(slot, physical, new LinkedHashSet<SlotId>(seen));
            boolean definition = names.ir().slotOwner(slot).map(owner -> owner instanceof Relations.DefinitionInstance).orElse(false);
            Optional<String> source = rendered(expression);
            if (source.map(BoundExpressionFormatter::star).orElse(false)) {
                return inherited;
            }
            if (!physical && boundary(slot)) {
                return source.isPresent() ? source : inherited;
            }
            if (physical || definition && complex(expression) || inherited.isPresent()
                    && (setSlot(slot, new LinkedHashSet<SlotId>()) || generatedSlot(slot, new LinkedHashSet<SlotId>()))) {
                return inherited;
            }
            return rendered(expression).isPresent() ? rendered(expression) : inherited;
        }
        if (physical && expression instanceof QualifiedFieldHandle) {
            QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
            return names.ir().object(field.getOwner()).flatMap(object -> names.column(
                    new ColumnEndpoint(object, field.getFieldIdentity().getId(), field.getFieldPath()), false));
        }
        Optional<String> source = rendered(expression);
        if (expression instanceof QualifiedFieldHandle && source.map(BoundExpressionFormatter::star).orElse(false)) {
            QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
            return names.ir().object(field.getOwner()).flatMap(object -> names.column(
                    new ColumnEndpoint(object, field.getFieldIdentity().getId(), field.getFieldPath()), false));
        }
        if (source.isPresent()) {
            return expression instanceof Expressions.Case ? source.map(ExpressionTextStyle::caseKeywords) : source;
        }
        if (expression instanceof QualifiedFieldHandle) {
            QualifiedFieldHandle field = (QualifiedFieldHandle) expression;
            return names.ir().object(field.getOwner()).flatMap(object -> names.column(
                    new ColumnEndpoint(object, field.getFieldIdentity().getId(), field.getFieldPath()), false));
        }
        if (expression instanceof Expressions.Call) {
            Expressions.Call call = (Expressions.Call) expression;
            List<String> arguments = new ArrayList<String>();
            for (BoundExpression argument : call.getArguments()) {
                arguments.add(expression(argument, physical, new LinkedHashSet<SlotId>(seen)).orElse("?"));
            }
            return Optional.of(call.getFunction().getName().getDisplayName() + "(" + String.join(", ", arguments) + ")");
        }
        return Optional.empty();
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

    private Optional<String> rendered(BoundExpression expression) {
        return expression.getSourceRef().flatMap(renderer::render).map(String::trim).filter(value -> !value.isEmpty());
    }

    private String literal(Expressions.Literal literal) {
        String value = literal.getValue().getValue().orElse("null");
        return literal.getValue().getKind() == Expressions.LiteralValue.Kind.STRING ? "'" + value.replace("'", "''") + "'" : value;
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
        if (!seen.add(slot) || boundary(slot)) {
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
            if (!literalSlot(slot, new LinkedHashSet<SlotId>())) {
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

    public boolean complex(BoundExpression expression) {
        BoundExpression origin = origin(expression);
        boolean set = hasSetProvenance(expression);
        boolean generated = origin instanceof Expressions.SlotRef
                && generatedSlot(((Expressions.SlotRef) origin).getSlotId(), new LinkedHashSet<SlotId>());
        return set || generated || !(origin instanceof Expressions.SlotRef) && !(origin instanceof QualifiedFieldHandle)
                && !(origin instanceof Expressions.Literal);
    }

    /** Only literal leaves and sets of literal leaves are constant assignments; calls are never folded here. */
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

    private boolean literalSlot(SlotId slot, Set<SlotId> seen) {
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

    private boolean boundary(SlotId slot) {
        return names.ir().slotOwner(slot).filter(owner -> owner instanceof Relations.DefinitionInstance)
                .map(owner -> ((Relations.DefinitionInstance) owner).getPhysicalObject().map(displayBoundary::test).orElse(false))
                .orElse(false);
    }

    private static boolean star(String source) {
        return "*".equals(source) || source.endsWith(".*");
    }
}
