package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.util.function.Consumer;
import static com.panfan.hivelineage.v2.analysis.RelationFactsEngine.facts;

/** Single implementations of built-in relation rules, selected through the same registry as extensions. */
final class BuiltInRelationRules {
    private static final Map<RuleKey, RelationLineageRule> RULES = rules();
    private static final RelationLineageRule UNKNOWN = rule(State::unknown);

    private BuiltInRelationRules() {
    }

    static Optional<RelationLineageRule> lookup(RuleKey key) {
        return Optional.ofNullable(RULES.get(key));
    }

    static RelationLineageRule unknown() {
        return UNKNOWN;
    }

    private static Map<RuleKey, RelationLineageRule> rules() {
        Map<RuleKey, RelationLineageRule> rules = new LinkedHashMap<RuleKey, RelationLineageRule>();
        rules.put(LineageRuleIdentity.ir("Scan"), rule(State::scan));
        rules.put(LineageRuleIdentity.ir("Values"), rule(State::values));
        rules.put(LineageRuleIdentity.ir("Filter"), rule(State::filter));
        rules.put(LineageRuleIdentity.ir("Join"), rule(State::join));
        rules.put(LineageRuleIdentity.ir("Aggregate"), rule(State::aggregate));
        rules.put(LineageRuleIdentity.ir("SetOp"), rule(State::setOp));
        rules.put(LineageRuleIdentity.ir("Window"), rule(State::window));
        rules.put(LineageRuleIdentity.ir("Generate"), rule(State::generate));
        rules.put(LineageRuleIdentity.ir("Sort"), rule(State::sort));
        rules.put(LineageRuleIdentity.ir("Limit"), rule(State::limit));
        rules.put(LineageRuleIdentity.ir("Distinct"), rule(State::distinct));
        rules.put(LineageRuleIdentity.ir("DefinitionInstance"), rule(State::definitionInstance));
        rules.put(LineageRuleIdentity.ir("UnknownRelation"), rule(State::unknown));
        rules.put(LineageRuleIdentity.ir("Project"), new CompositionalProjectRule());
        rules.put(LineageRuleIdentity.ir("ExternalDatasetRelation"), new ExternalDatasetLineageRule());
        return Collections.unmodifiableMap(rules);
    }

    private static RelationLineageRule rule(Consumer<State> operation) {
        return new BuiltInRule(operation);
    }

    /** These rules resolve expressions lazily, because predicates can refine their input row environment. */
    static final class BuiltInRule implements RelationLineageRule {
        private final Consumer<State> operation;

        private BuiltInRule(Consumer<State> operation) {
            this.operation = operation;
        }

        @Override
        public RelationFacts analyze(BoundRelation relation, List<RelationFacts> inputs,
                Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context) {
            State state = new State(relation, inputs, expressions, context);
            operation.accept(state);
            return state.finish();
        }
    }

    private static final class State {
        private final BoundRelation relation;
        private final List<RelationFacts> inputs;
        private final LineageRuleContext context;
        private final Map<NodeId, ExpressionFacts> analyzedExpressions;
        private final EvidenceFactory support;
        private final NodeId id;
        private final Map<SlotId, ExpressionFacts> slots;
        private final Map<SlotId, ExpressionFacts> output = new LinkedHashMap<SlotId, ExpressionFacts>();
        private final List<Dependency> rows;
        private final RowSetEndpoint rowTarget;

        private State(BoundRelation relation, List<RelationFacts> inputs, Map<NodeId, ExpressionFacts> analyzedExpressions, LineageRuleContext context) {
            this.relation = relation;
            this.inputs = inputs;
            this.context = context;
            this.analyzedExpressions = analyzedExpressions;
            this.support = context.getEvidenceFactory();
            this.id = relation.getId();
            this.slots = context.getInputSlots();
            this.rows = new ArrayList<Dependency>(context.getInputRows());
            this.rowTarget = new RowSetEndpoint(new RelationInstanceId("rowset:" + id.getValue()));
            if (!context.hasExpressionEnvironment()) {
                for (RelationFacts input : inputs) {
                    rows.addAll(support.retarget(input.getRowSetFacts(), rowTarget, id, "relation.rows", null, relation.getSourceRef()));
                }
            }
        }

        private ExpressionFacts resolve(BoundExpression expression, Map<SlotId, ExpressionFacts> inputs, List<Dependency> inputRows) {
            return context.resolve(expression, inputs, inputRows, analyzedExpressions);
        }

        private void scan() {
            Relations.Scan scan = (Relations.Scan) relation;
            rows.add(support.dependency(new RowSetEndpoint(scan.getInstanceId()), rowTarget, DependencyRole.ROWSET,
                    DependencyPrecision.STRUCTURAL, id, "scan.rows", Collections.emptyList(), scan.getSourceRef()));
            for (OutputSlot slot : IrTraversal.knownSlots(scan.getOutputSchema())) {
                LineageEndpoint source = slot.getFieldIdentity().isPresent()
                        ? new ColumnEndpoint(scan.getObjectId(), slot.getFieldIdentity().get().getId(), Collections.emptyList())
                        : new UnknownEndpoint(FactSupport.diagnostic("SCAN_FIELD_IDENTITY_MISSING", "Scan field lacks a declared identity", null));
                List<Dependency> dependencies = Collections.singletonList(support.dependency(source, new SlotEndpoint(slot.getId()),
                        DependencyRole.VALUE, source instanceof UnknownEndpoint ? DependencyPrecision.UNKNOWN : DependencyPrecision.STRUCTURAL,
                        id, "scan.column", Collections.emptyList(), scan.getSourceRef()));
                output.put(slot.getId(), facts(id, dependencies));
            }
        }

        private void values() {
            List<OutputSlot> declared = IrTraversal.knownSlots(relation.getOutputSchema());
            for (int position = 0; position < declared.size(); position++) {
                SlotId target = declared.get(position).getId();
                List<Dependency> dependencies = new ArrayList<>();
                for (List<BoundExpression> row : ((Relations.Values) relation).getRows()) {
                    dependencies.addAll(support.retarget(resolve(row.get(position), slots, rows).getDependencies(),
                            new SlotEndpoint(target), id, "values", null, relation.getSourceRef()));
                }
                output.put(target, facts(id, dependencies));
            }
        }

        private void filter() {
            Relations.Filter filter = (Relations.Filter) relation;
            mappings(filter.getMappings(), relation, slots, output, "filter.passthrough");
            predicate(filter.getPredicate(), relation, slots, rows, rowTarget, "filter.condition");
        }

        private void join() {
            Relations.Join join = (Relations.Join) relation;
            mappings(join.getMappings(), relation, slots, output, "join.passthrough");
            join.getCondition().ifPresent(condition -> predicate(condition, relation, slots, rows, rowTarget, "join.condition"));
            if (!join.getCondition().isPresent()) {
                for (Relations.JoinPredicate predicate : join.getPredicates()) {
                    predicate(predicate.getExpression(), relation, slots, rows, rowTarget, "join.condition");
                }
            }
        }

        private void aggregate() {
            Relations.Aggregate aggregate = (Relations.Aggregate) relation;
            for (BoundExpression grouping : aggregate.getGroupings()) {
                predicate(grouping, relation, slots, rows, rowTarget, "aggregate.grouping");
            }
            assignments(aggregate.getAssignments(), relation, slots, rows, output, "aggregate.output");
        }

        private void setOp() {
            Relations.SetOp set = (Relations.SetOp) relation;
            for (Relations.SetOutputMapping mapping : set.getMappings()) {
                List<Dependency> dependencies = new ArrayList<Dependency>();
                for (int branch = 0; branch < mapping.getBranchSlots().size(); branch++) {
                    Relations.BranchSlot branchSlot = mapping.getBranchSlots().get(branch);
                    if (branchSlot.getSlot().isPresent()) {
                        ExpressionFacts source = inputs.get(branch).getOutputFacts().get(branchSlot.getSlot().get());
                        dependencies.addAll(support.retarget(source.getDependencies(), new SlotEndpoint(mapping.getOutput()), id,
                                "set." + set.getOperator() + "." + set.getDuplicatePolicy(), null, relation.getSourceRef()));
                    } else {
                        dependencies.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic(branchSlot.getDiagnosticCode().orElse("SET_BRANCH_UNKNOWN"),
                                "Set branch output is unknown", null)), new SlotEndpoint(mapping.getOutput()), DependencyRole.VALUE,
                                DependencyPrecision.UNKNOWN, id, "set.unknown", Collections.emptyList(), relation.getSourceRef()));
                    }
                }
                output.put(mapping.getOutput(), facts(id, dependencies));
            }
        }

        private void window() {
            Relations.Window window = (Relations.Window) relation;
            mappings(window.getRetainedSlots(), relation, slots, output, "window.passthrough");
            for (Relations.WindowComputation computation : window.getComputations()) {
                SlotId target = computation.getAssignment().getOutput();
                List<Dependency> dependencies = new ArrayList<Dependency>(support.retarget(resolve(
                        computation.getAssignment().getExpression(), slots, rows).getDependencies(), new SlotEndpoint(target), id,
                        "window.value", null, relation.getSourceRef()));
                for (BoundExpression partition : computation.getPartitions()) {
                    dependencies.addAll(support.retarget(resolve(partition, slots, rows).getDependencies(), new SlotEndpoint(target), id,
                            "window.partition", DependencyRole.CONDITION, relation.getSourceRef()));
                }
                for (Relations.OrderKey order : computation.getOrderKeys()) {
                    dependencies.addAll(support.retarget(resolve(order.getExpression(), slots, rows).getDependencies(), new SlotEndpoint(target), id,
                            "window.order", DependencyRole.CONDITION, relation.getSourceRef()));
                }
                if (computation.getFrame().isPresent()) {
                    List<BoundExpression> offsets = new ArrayList<BoundExpression>();
                    computation.getFrame().get().getStart().getOffset().ifPresent(offsets::add);
                    computation.getFrame().get().getEnd().getOffset().ifPresent(offsets::add);
                    for (BoundExpression offset : offsets) {
                        dependencies.addAll(support.retarget(resolve(offset, slots, rows).getDependencies(), new SlotEndpoint(target), id,
                                "window.frame", DependencyRole.CONDITION, relation.getSourceRef()));
                    }
                }
                output.put(target, facts(id, dependencies));
            }
        }

        private void generate() {
            Relations.Generate generate = (Relations.Generate) relation;
            mappings(generate.getRetainedSlots(), relation, slots, output, "generate.passthrough");
            List<Dependency> arguments = new ArrayList<Dependency>();
            for (BoundExpression argument : generate.getArguments()) {
                arguments.addAll(resolve(argument, slots, rows).getDependencies());
            }
            for (SlotId target : generate.getGeneratedSlots()) {
                List<Dependency> dependencies = new ArrayList<Dependency>(support.retarget(arguments, new SlotEndpoint(target), id,
                        "generate.value", null, relation.getSourceRef()));
                dependencies.addAll(support.retarget(rows, new SlotEndpoint(target), id, "generate.rows", DependencyRole.ROWSET, relation.getSourceRef()));
                if (generate.getFunction().getKind() == FunctionSymbol.FunctionKind.OPAQUE) {
                    dependencies.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic("OPAQUE_GENERATOR", "Generator behavior is unknown", null)),
                            new SlotEndpoint(target), DependencyRole.VALUE, DependencyPrecision.UNKNOWN, id, "generate.unknown", Collections.emptyList(), relation.getSourceRef()));
                }
                output.put(target, facts(id, dependencies));
            }
            for (BoundExpression argument : generate.getArguments()) {
                predicate(argument, relation, slots, rows, rowTarget, "generate.rowset");
            }
        }

        private void sort() {
            Relations.Sort sort = (Relations.Sort) relation;
            mappings(sort.getMappings(), relation, slots, output, "sort.passthrough");
            for (Relations.OrderKey key : sort.getOrderKeys()) {
                predicate(key.getExpression(), relation, slots, rows, rowTarget, "sort.order");
            }
        }

        private void limit() {
            Relations.Limit limit = (Relations.Limit) relation;
            mappings(limit.getMappings(), relation, slots, output, "limit.passthrough");
            predicate(limit.getCount(), relation, slots, rows, rowTarget, "limit.count");
            limit.getOffset().ifPresent(offset -> predicate(offset, relation, slots, rows, rowTarget, "limit.offset"));
        }

        private void distinct() {
            Relations.Distinct distinct = (Relations.Distinct) relation;
            mappings(distinct.getMappings(), relation, slots, output, "distinct.passthrough");
            for (SlotId key : distinct.getKeys()) {
                rows.addAll(support.retarget(slots.get(key).getDependencies(), rowTarget, id, "distinct.keys", DependencyRole.CONDITION, relation.getSourceRef()));
            }
        }

        private void definitionInstance() {
            Relations.DefinitionInstance definition = (Relations.DefinitionInstance) relation;
            mappings(definition.getMappings(), relation, slots, output, "definition.expand");
            if (definition.getPhysicalObject().isPresent()) {
                rows.add(support.dependency(new RowSetEndpoint(definition.getInstanceId()), rowTarget, DependencyRole.DEFINITION,
                        DependencyPrecision.STRUCTURAL, id, "definition.boundary", Collections.emptyList(), relation.getSourceRef()));
                for (OutputSlot slot : IrTraversal.knownSlots(definition.getOutputSchema())) {
                    if (slot.getFieldIdentity().isPresent() && output.containsKey(slot.getId())) {
                        List<Dependency> dependencies = new ArrayList<Dependency>(output.get(slot.getId()).getDependencies());
                        dependencies.add(support.dependency(new ColumnEndpoint(definition.getPhysicalObject().get(), slot.getFieldIdentity().get().getId(), Collections.emptyList()),
                                new SlotEndpoint(slot.getId()), DependencyRole.DEFINITION, DependencyPrecision.STRUCTURAL, id, "definition.boundary", Collections.emptyList(), relation.getSourceRef()));
                        output.put(slot.getId(), facts(id, dependencies));
                    }
                }
            }
        }

        private void unknown() {
            rows.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic("UNKNOWN_RELATION", "Relation behavior is unknown", null)),
                    rowTarget, DependencyRole.ROWSET, DependencyPrecision.UNKNOWN, id, "unknown.relation", Collections.emptyList(), relation.getSourceRef()));
        }

        private RelationFacts finish() {
            if (RelationFactsEngine.hasUnknownWidth(relation)) {
                rows.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic("OUTPUT_SCHEMA_UNKNOWN", "Relation has uncertain output width", null)),
                        rowTarget, DependencyRole.SCHEMA, DependencyPrecision.UNKNOWN, id, "schema.unknown", Collections.emptyList(), relation.getSourceRef()));
            }
            return new RelationFacts(id, output, FactSupport.distinct(rows), FactSupport.diagnostics(rows));
        }

        private void assignments(List<OutputAssignment> assignments, BoundRelation relation, Map<SlotId, ExpressionFacts> inputs,
                List<Dependency> rows, Map<SlotId, ExpressionFacts> output, String rule) {
            for (OutputAssignment assignment : assignments) {
                List<Dependency> dependencies = support.retarget(resolve(assignment.getExpression(), inputs, rows).getDependencies(),
                        new SlotEndpoint(assignment.getOutput()), relation.getId(), rule, null, relation.getSourceRef());
                output.put(assignment.getOutput(), facts(assignment.getExpression().getId(), dependencies));
            }
        }

        private void mappings(List<SlotMapping> mappings, BoundRelation relation, Map<SlotId, ExpressionFacts> inputs,
                Map<SlotId, ExpressionFacts> output, String rule) {
            for (SlotMapping mapping : mappings) {
                ExpressionFacts facts = inputs.get(mapping.getInput());
                output.put(mapping.getOutput(), facts(relation.getId(), support.retarget(facts.getDependencies(), new SlotEndpoint(mapping.getOutput()),
                        relation.getId(), rule, null, relation.getSourceRef())));
            }
        }

        private void predicate(BoundExpression predicate, BoundRelation relation, Map<SlotId, ExpressionFacts> inputs,
                List<Dependency> rows, RowSetEndpoint target, String rule) {
            List<Dependency> proof = resolve(predicate, inputs, new ArrayList<Dependency>(rows)).getDependencies();
            rows.addAll(support.retarget(proof, target, relation.getId(), rule, DependencyRole.CONDITION, relation.getSourceRef()));
        }

    }
}
