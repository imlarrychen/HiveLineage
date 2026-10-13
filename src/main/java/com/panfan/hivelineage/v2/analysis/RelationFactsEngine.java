package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.IrTraversal;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One-analysis memoized relation algebra; state never escapes into a shared Session. */
final class RelationFactsEngine implements ExpressionFactsResolver.Upstream {
    private final BoundAnalysisUnit unit;
    private final PureIrIndex index;
    private final FactSupport support;
    private final LineageRuleRegistry rules;
    private final ExpressionFactsResolver expressions;
    private final Map<NodeId, RelationFacts> cache = new LinkedHashMap<NodeId, RelationFacts>();

    RelationFactsEngine(BoundAnalysisUnit unit, LineageRuleRegistry rules) {
        this(unit, rules, Integer.MAX_VALUE);
    }

    RelationFactsEngine(BoundAnalysisUnit unit, LineageRuleRegistry rules, int maxEvidenceEdges) {
        this.support = new FactSupport(maxEvidenceEdges);
        this.unit = unit;
        this.rules = rules;
        this.index = new PureIrIndex(unit);
        this.expressions = new ExpressionFactsResolver(index, this, support, rules);
    }

    FactSupport support() {
        return support;
    }

    ExpressionFacts expression(BoundExpression expression, Map<SlotId, ExpressionFacts> slots, List<Dependency> rows) {
        return expressions.resolve(expression, slots, rows);
    }

    @Override
    public ExpressionFacts slot(SlotId id) {
        if (index.slotOwner(id).isPresent()) {
            RelationFacts relation = relation(index.slotOwner(id).get().getId());
            if (relation.getOutputFacts().containsKey(id)) {
                return relation.getOutputFacts().get(id);
            }
        }
        List<Dependency> unknown = Collections.singletonList(support.dependency(new UnknownEndpoint(
                FactSupport.diagnostic("SLOT_FACTS_UNKNOWN", "No proven facts for bound slot", null)), new SlotEndpoint(id),
                DependencyRole.VALUE, DependencyPrecision.UNKNOWN, new NodeId("unknown-slot:" + id.getValue()),
                "unknown.slot", Collections.emptyList(), Optional.empty()));
        return facts(new NodeId("unknown-slot:" + id.getValue()), unknown);
    }

    @Override
    public RelationFacts relation(NodeId id) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        BoundRelation relation = index.getRelations().get(id);
        if (relation == null) {
            throw new IllegalArgumentException("Verified graph has no relation " + id.getValue());
        }
        List<RelationFacts> inputs = new ArrayList<RelationFacts>();
        Map<SlotId, ExpressionFacts> slots = new LinkedHashMap<SlotId, ExpressionFacts>();
        List<Dependency> rows = new ArrayList<Dependency>();
        RowSetEndpoint rowTarget = new RowSetEndpoint(new RelationInstanceId("rowset:" + id.getValue()));
        for (NodeId input : relation.getInputIds()) {
            RelationFacts inputFacts = relation(input);
            inputs.add(inputFacts);
            slots.putAll(inputFacts.getOutputFacts());
            rows.addAll(support.retarget(inputFacts.getRowSetFacts(), rowTarget, id, "relation.rows", null, relation.getSourceRef()));
        }
        Optional<RelationLineageRule> custom = rules.relationRule(new RuleKey("ir", relation.getClass().getSimpleName(), "1"));
        if (custom.isPresent()) {
            Map<NodeId, ExpressionFacts> arguments = new LinkedHashMap<NodeId, ExpressionFacts>();
            for (BoundExpression expression : IrTraversal.expressions(relation)) {
                arguments.put(expression.getId(), expressions.resolve(expression, slots, rows));
            }
            RelationFacts result = custom.get().analyze(relation, inputs, arguments, new LineageRuleContext("1", slots, support));
            support.capture(result.getRowSetFacts());
            for (ExpressionFacts output : result.getOutputFacts().values()) {
                support.capture(output.getDependencies());
            }
            cache.put(id, result);
            return result;
        }
        Map<SlotId, ExpressionFacts> output = new LinkedHashMap<SlotId, ExpressionFacts>();
        if (relation instanceof Relations.Scan) {
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
        } else if (relation instanceof Relations.Values) {
            List<OutputSlot> declared = IrTraversal.knownSlots(relation.getOutputSchema());
            for (int position = 0; position < declared.size(); position++) {
                SlotId target = declared.get(position).getId();
                List<Dependency> dependencies = new ArrayList<>();
                for (List<BoundExpression> row : ((Relations.Values) relation).getRows()) {
                    dependencies.addAll(support.retarget(expressions.resolve(row.get(position), slots, rows).getDependencies(),
                            new SlotEndpoint(target), id, "values", null, relation.getSourceRef()));
                }
                output.put(target, facts(id, dependencies));
            }
        } else if (relation instanceof Relations.Project) {
            assignments(((Relations.Project) relation).getAssignments(), relation, slots, rows, output, "project");
        } else if (relation instanceof Relations.Filter) {
            Relations.Filter filter = (Relations.Filter) relation;
            mappings(filter.getMappings(), relation, slots, output, "filter.passthrough");
            predicate(filter.getPredicate(), relation, slots, rows, rowTarget, "filter.condition");
        } else if (relation instanceof Relations.Join) {
            Relations.Join join = (Relations.Join) relation;
            mappings(join.getMappings(), relation, slots, output, "join.passthrough");
            join.getCondition().ifPresent(condition -> predicate(condition, relation, slots, rows, rowTarget, "join.condition"));
            if (!join.getCondition().isPresent()) {
                for (Relations.JoinPredicate predicate : join.getPredicates()) {
                    predicate(predicate.getExpression(), relation, slots, rows, rowTarget, "join.condition");
                }
            }
        } else if (relation instanceof Relations.Aggregate) {
            Relations.Aggregate aggregate = (Relations.Aggregate) relation;
            for (BoundExpression grouping : aggregate.getGroupings()) {
                predicate(grouping, relation, slots, rows, rowTarget, "aggregate.grouping");
            }
            assignments(aggregate.getAssignments(), relation, slots, rows, output, "aggregate.output");
        } else if (relation instanceof Relations.SetOp) {
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
        } else if (relation instanceof Relations.Window) {
            Relations.Window window = (Relations.Window) relation;
            mappings(window.getRetainedSlots(), relation, slots, output, "window.passthrough");
            for (Relations.WindowComputation computation : window.getComputations()) {
                SlotId target = computation.getAssignment().getOutput();
                List<Dependency> dependencies = new ArrayList<Dependency>(support.retarget(expressions.resolve(
                        computation.getAssignment().getExpression(), slots, rows).getDependencies(), new SlotEndpoint(target), id,
                        "window.value", null, relation.getSourceRef()));
                for (BoundExpression partition : computation.getPartitions()) {
                    dependencies.addAll(support.retarget(expressions.resolve(partition, slots, rows).getDependencies(), new SlotEndpoint(target), id,
                            "window.partition", DependencyRole.CONDITION, relation.getSourceRef()));
                }
                for (Relations.OrderKey order : computation.getOrderKeys()) {
                    dependencies.addAll(support.retarget(expressions.resolve(order.getExpression(), slots, rows).getDependencies(), new SlotEndpoint(target), id,
                            "window.order", DependencyRole.CONDITION, relation.getSourceRef()));
                }
                if (computation.getFrame().isPresent()) {
                    List<BoundExpression> offsets = new ArrayList<BoundExpression>();
                    computation.getFrame().get().getStart().getOffset().ifPresent(offsets::add);
                    computation.getFrame().get().getEnd().getOffset().ifPresent(offsets::add);
                    for (BoundExpression offset : offsets) {
                        dependencies.addAll(support.retarget(expressions.resolve(offset, slots, rows).getDependencies(), new SlotEndpoint(target), id,
                                "window.frame", DependencyRole.CONDITION, relation.getSourceRef()));
                    }
                }
                output.put(target, facts(id, dependencies));
            }
        } else if (relation instanceof Relations.Generate) {
            Relations.Generate generate = (Relations.Generate) relation;
            mappings(generate.getRetainedSlots(), relation, slots, output, "generate.passthrough");
            List<Dependency> arguments = new ArrayList<Dependency>();
            for (BoundExpression argument : generate.getArguments()) {
                arguments.addAll(expressions.resolve(argument, slots, rows).getDependencies());
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
        } else if (relation instanceof Relations.Sort) {
            Relations.Sort sort = (Relations.Sort) relation;
            mappings(sort.getMappings(), relation, slots, output, "sort.passthrough");
            for (Relations.OrderKey key : sort.getOrderKeys()) {
                predicate(key.getExpression(), relation, slots, rows, rowTarget, "sort.order");
            }
        } else if (relation instanceof Relations.Limit) {
            Relations.Limit limit = (Relations.Limit) relation;
            mappings(limit.getMappings(), relation, slots, output, "limit.passthrough");
            predicate(limit.getCount(), relation, slots, rows, rowTarget, "limit.count");
            limit.getOffset().ifPresent(offset -> predicate(offset, relation, slots, rows, rowTarget, "limit.offset"));
        } else if (relation instanceof Relations.Distinct) {
            Relations.Distinct distinct = (Relations.Distinct) relation;
            mappings(distinct.getMappings(), relation, slots, output, "distinct.passthrough");
            for (SlotId key : distinct.getKeys()) {
                rows.addAll(support.retarget(slots.get(key).getDependencies(), rowTarget, id, "distinct.keys", DependencyRole.CONDITION, relation.getSourceRef()));
            }
        } else if (relation instanceof Relations.DefinitionInstance) {
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
        } else {
            rows.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic("UNKNOWN_RELATION", "Relation behavior is unknown", null)),
                    rowTarget, DependencyRole.ROWSET, DependencyPrecision.UNKNOWN, id, "unknown.relation", Collections.emptyList(), relation.getSourceRef()));
        }
        if (hasUnknownWidth(relation)) {
            rows.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic("OUTPUT_SCHEMA_UNKNOWN", "Relation has uncertain output width", null)),
                    rowTarget, DependencyRole.SCHEMA, DependencyPrecision.UNKNOWN, id, "schema.unknown", Collections.emptyList(), relation.getSourceRef()));
        }
        RelationFacts result = new RelationFacts(id, output, FactSupport.distinct(rows), FactSupport.diagnostics(rows));
        cache.put(id, result);
        return result;
    }

    private void assignments(List<OutputAssignment> assignments, BoundRelation relation, Map<SlotId, ExpressionFacts> inputs,
            List<Dependency> rows, Map<SlotId, ExpressionFacts> output, String rule) {
        for (OutputAssignment assignment : assignments) {
            List<Dependency> dependencies = support.retarget(expressions.resolve(assignment.getExpression(), inputs, rows).getDependencies(),
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
        List<Dependency> proof = expressions.resolve(predicate, inputs, new ArrayList<Dependency>(rows)).getDependencies();
        rows.addAll(support.retarget(proof, target, relation.getId(), rule, DependencyRole.CONDITION, relation.getSourceRef()));
    }

    static ExpressionFacts facts(NodeId id, List<Dependency> dependencies) {
        List<Dependency> distinct = FactSupport.distinct(dependencies);
        return new ExpressionFacts(id, distinct, FactSupport.quality(distinct), FactSupport.diagnostics(distinct));
    }

    static boolean hasUnknownWidth(BoundRelation relation) {
        if (relation.getOutputSchema().getState() != SchemaState.UNKNOWN) {
            return false;
        }
        if (relation.getOutputSchema().getSegments().isEmpty()) {
            return true;
        }
        for (OutputSegment segment : relation.getOutputSchema().getSegments()) {
            if (segment instanceof UnknownOutputSegment) {
                return true;
            }
        }
        return false;
    }
}
