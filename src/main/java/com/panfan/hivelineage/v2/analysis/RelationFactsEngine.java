package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One-analysis memoized relation algebra; state never escapes into a shared Session.
 */
final class RelationFactsEngine implements ExpressionFactsResolver.Upstream {
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
        RuleKey key = LineageRuleIdentity.relation(relation);
        RelationLineageRule rule = rules.relationRule(key).orElseGet(() ->
                BuiltInRelationRules.lookup(key).orElseGet(BuiltInRelationRules::unknown));
        RelationFacts result = rule.evaluate(relation, inputs,
                new RelationEvaluationContext(LineageRuleIdentity.VERSION, slots, rows, support, expressions::resolve));
        support.capture(result.getRowSetFacts());
        for (ExpressionFacts output : result.getOutputFacts().values()) {
            support.capture(output.getDependencies());
        }
        cache.put(id, result);
        return result;
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
