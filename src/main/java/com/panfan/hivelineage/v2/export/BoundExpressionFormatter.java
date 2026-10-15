package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.SlotProvenanceIndex;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Text projection over bound structure; rendering never changes a dependency.
 */
public final class BoundExpressionFormatter {
    private final SourceRenderer renderer;
    private final BoundNameIndex names;
    private final Map<SlotId, BoundExpression> assignments;
    private final Map<SlotId, SlotId> mappings;
    private final Map<SlotId, Relations.SetOutputMapping> sets;
    private final Map<SlotId, String> setNames;
    private final Map<SlotId, Relations.Generate> generators;
    private final ExpressionShapeAnalyzer shapes;
    private final FunctionUsageCollector functionUsage;

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
        this.assignments = provenance.assignments();
        this.mappings = provenance.mappings();
        this.sets = provenance.sets();
        this.setNames = provenance.setNames();
        this.generators = provenance.generators();
        this.shapes = new ExpressionShapeAnalyzer(provenance, names, displayBoundary);
        this.functionUsage = new FunctionUsageCollector(provenance, shapes);
    }

    public Optional<String> format(BoundExpression expression) {
        if (expression instanceof Expressions.SlotRef) {
            return slot(((Expressions.SlotRef) expression).getSlotId(), false, new LinkedHashSet<SlotId>());
        }
        return expression(expression, false, new LinkedHashSet<SlotId>());
    }

    /**
     * A synthesized display string does not establish that expression text was extracted.
     */
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
                        && (shapes.setSlot(reference.getSlotId()) || definition);
                return inherited ? extractedSlot(reference.getSlotId(), seen) : rendered(expression).isPresent();
            }
            return rendered(expression).isPresent();
        }
        return mappings.containsKey(slot) && extractedSlot(mappings.get(slot), seen);
    }

    public ExpressionShapeAnalyzer shapes() {
        return shapes;
    }

    public FunctionUsageCollector functionUsage() {
        return functionUsage;
    }

    public BoundExpression origin(BoundExpression expression) {
        return shapes.origin(expression);
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
                    && (shapes.setSlot(slot) || shapes.generatedSlot(slot))) {
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

    public boolean hasSetProvenance(BoundExpression expression) {
        return shapes.hasSetProvenance(expression);
    }

    private Optional<String> rendered(BoundExpression expression) {
        return expression.getSourceRef().flatMap(renderer::render).map(String::trim).filter(value -> !value.isEmpty());
    }

    private String literal(Expressions.Literal literal) {
        String value = literal.getValue().getValue().orElse("null");
        return literal.getValue().getKind() == Expressions.LiteralValue.Kind.STRING ? "'" + value.replace("'", "''") + "'" : value;
    }

    public List<String> functions(BoundExpression expression) {
        return functionUsage.functions(expression);
    }

    public List<String> functionsWithOccurrences(BoundExpression expression) {
        return functionUsage.functionsWithOccurrences(expression);
    }

    public boolean complex(BoundExpression expression) {
        return shapes.complex(expression);
    }

    public boolean literalOnly(BoundExpression expression) {
        return shapes.literalOnly(expression);
    }

    private boolean boundary(SlotId slot) {
        return shapes.boundary(slot);
    }

    private static boolean star(String source) {
        return "*".equals(source) || source.endsWith(".*");
    }
}
