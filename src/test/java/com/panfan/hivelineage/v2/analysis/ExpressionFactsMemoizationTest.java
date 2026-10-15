package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class ExpressionFactsMemoizationTest {
    @Test
    public void sharedDagEvaluatesEveryUniqueExpressionOnce() {
        AtomicInteger visits = new AtomicInteger();
        BoundExpression expression = AnalysisFixtures.literal("leaf", "1");
        for (int depth = 0; depth < 45; depth++) {
            expression = AnalysisFixtures.call("dag-" + depth, "pair", FunctionSymbol.FunctionKind.OPERATOR, expression, expression);
        }
        ExpressionFacts result = resolver(countingRules(visits), new FactSupport()).resolve(expression,
                Collections.emptyMap(), Collections.emptyList());
        Assert.assertEquals(45, visits.get());
        Assert.assertEquals(1, result.getDependencies().size());
        Assert.assertTrue(result.getDependencies().get(0).getSource() instanceof ConstantEndpoint);
    }

    @Test
    public void memoNeverReusesFactsAfterSlotOrRowEnvironmentChanges() {
        FactSupport support = new FactSupport();
        ExpressionFactsResolver resolver = resolver(new DefaultLineageRuleRegistry(), support);
        Expressions.SlotRef ref = AnalysisFixtures.ref("ref", "input");
        Map<SlotId, ExpressionFacts> slots = new LinkedHashMap<SlotId, ExpressionFacts>();
        slots.put(new SlotId("input"), value(support, "first"));
        Assert.assertEquals("first", constant(resolver.resolve(ref, slots, Collections.emptyList())));
        slots.put(new SlotId("input"), value(support, "second"));
        Assert.assertEquals("second", constant(resolver.resolve(ref, slots, Collections.emptyList())));
        Expressions.Call count = AnalysisFixtures.call("count", "count", FunctionSymbol.FunctionKind.AGGREGATE);
        Assert.assertTrue(resolver.resolve(count, slots, Collections.emptyList()).getDependencies().isEmpty());
        Dependency row = support.dependency(new RowSetEndpoint(new RelationInstanceId("rows")), new SlotEndpoint(new SlotId("input")),
                DependencyRole.ROWSET, DependencyPrecision.STRUCTURAL, new NodeId("rows"), "test.rows", Collections.emptyList(), Optional.empty());
        Assert.assertEquals(1, resolver.resolve(count, slots, Collections.singletonList(row)).getDependencies().size());
    }

    @Test
    public void writeConditionIsEvaluatedOnceAndRedirectedToEveryTarget() {
        AtomicInteger visits = new AtomicInteger();
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        Expressions.Call condition = AnalysisFixtures.call("condition", "pair", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("condition.input", "scan.id"));
        List<WriteAssignment> assignments = new ArrayList<WriteAssignment>();
        for (int column = 0; column < 80; column++) {
            assignments.add(new WriteAssignment(new FieldIdentity("target-" + column, "field" + column),
                    AnalysisFixtures.literal("value-" + column, "fixed")));
        }
        BoundWrite write = new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.notRequested(), VersionRef.notRequested(),
                BoundWrite.WriteMode.APPEND, assignments, new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()),
                condition, MappingCoverage.COMPLETE, Collections.emptyList(), scan.getId());
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.singletonList(scan), Collections.singletonList(write));
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit,
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA, BindingRequirement.SINK_MAPPING), countingRules(visits));
        Assert.assertEquals(1, visits.get());
        Assert.assertEquals(80, facts.getWriteDependencies().stream().filter(dependency ->
                "write.condition".equals(dependency.getEvidence().getRuleId()) && dependency.getRole() == DependencyRole.CONDITION).count());
    }

    private static LineageRuleRegistry countingRules(AtomicInteger visits) {
        Map<RuleKey, ExpressionLineageRule> rules = new LinkedHashMap<RuleKey, ExpressionLineageRule>();
        rules.put(new RuleKey("function", "operator.PAIR", "1"), (expression, arguments, context) -> {
            visits.incrementAndGet();
            return new CompositionalExpressionRule("operator.PAIR").analyze(expression, arguments, context);
        });
        return new DefaultLineageRuleRegistry(rules, Collections.emptyMap());
    }

    private static ExpressionFactsResolver resolver(LineageRuleRegistry rules, FactSupport support) {
        PureIrIndex index = new PureIrIndex(AnalysisFixtures.unit(Collections.emptyList(), Collections.emptyList()));
        return new ExpressionFactsResolver(index, new ExpressionFactsResolver.Upstream() {
            @Override
            public RelationFacts relation(NodeId id) {
                throw new AssertionError("No upstream relation expected");
            }
            @Override
            public ExpressionFacts slot(SlotId id) {
                throw new AssertionError("No upstream slot expected");
            }
        }, support, rules);
    }

    private static ExpressionFacts value(FactSupport support, String value) {
        return RelationFactsEngine.facts(new NodeId(value), Collections.singletonList(support.dependency(new ConstantEndpoint(value),
                new SlotEndpoint(new SlotId("input")), DependencyRole.VALUE, DependencyPrecision.STRUCTURAL,
                new NodeId(value), "test.value", Collections.emptyList(), Optional.empty())));
    }

    private static String constant(ExpressionFacts facts) {
        return ((ConstantEndpoint) facts.getDependencies().get(0).getSource()).getValueRepresentation();
    }
}
