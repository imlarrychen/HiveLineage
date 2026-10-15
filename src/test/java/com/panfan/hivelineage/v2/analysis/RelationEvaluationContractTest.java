package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

/** Extension rules use the same public capability contract as built-in relation rules. */
public class RelationEvaluationContractTest {
    @Test
    public void replacementCanSkipExpressionEvaluationWithoutConcreteImplementationIdentity() {
        AtomicInteger evaluations = new AtomicInteger();
        ExpressionLineageRule expression = (value, arguments, context) -> {
            evaluations.incrementAndGet();
            return RelationFactsEngine.facts(value.getId(), Collections.emptyList());
        };
        EvaluatingRelationLineageRule lazy = (relation, inputs, context) -> {
            Assert.assertEquals("1", context.getRulesVersion());
            Assert.assertFalse(context.getInputRows().isEmpty());
            return new RelationFacts(relation.getId(), context.getInputSlots(), context.getInputRows(), DiagnosticBag.empty());
        };
        RelationFactsEngine engine = engine(expression, lazy);
        Assert.assertEquals(1, engine.relation(new NodeId("filter")).getOutputFacts().size());
        engine.relation(new NodeId("filter"));
        Assert.assertEquals(0, evaluations.get());
    }

    @Test
    public void legacyRuleStillReceivesPreAnalyzedExpressionsAndImmutableInputEnvironment() {
        AtomicInteger evaluations = new AtomicInteger();
        ExpressionLineageRule expression = (value, arguments, context) -> {
            evaluations.incrementAndGet();
            return RelationFactsEngine.facts(value.getId(), Collections.emptyList());
        };
        RelationLineageRule legacy = (relation, inputs, arguments, context) -> {
            Assert.assertTrue(arguments.containsKey(new NodeId("predicate")));
            Assert.assertEquals(1, context.getInputSlots().size());
            Assert.assertFalse(context.getInputRows().isEmpty());
            return new RelationFacts(relation.getId(), context.getInputSlots(), context.getInputRows(), DiagnosticBag.empty());
        };
        engine(expression, legacy).relation(new NodeId("filter"));
        Assert.assertEquals(1, evaluations.get());
    }

    @Test
    public void explicitEnvironmentsKeepSlotAndRowFactsIndependentAcrossEvaluations() {
        Relations.Scan first = AnalysisFixtures.scan("first", "a", "main", "id");
        Relations.Scan second = AnalysisFixtures.scan("second", "b", "main", "id");
        RelationFactsEngine engine = new RelationFactsEngine(AnalysisFixtures.unit(Arrays.asList(first, second), Collections.emptyList()),
                new DefaultLineageRuleRegistry());
        RelationFacts firstFacts = engine.relation(first.getId());
        RelationFacts secondFacts = engine.relation(second.getId());
        Map<SlotId, ExpressionFacts> slots = new LinkedHashMap<SlotId, ExpressionFacts>(firstFacts.getOutputFacts());
        RelationEvaluationContext context = new RelationEvaluationContext("1", slots, firstFacts.getRowSetFacts(),
                engine.support(), engine::expression);
        slots.put(new SlotId("first.id"), secondFacts.getOutputFacts().get(new SlotId("second.id")));
        Expressions.SlotRef reference = AnalysisFixtures.ref("reference", "first.id");
        RelationEvaluationContext.Environment other = new RelationEvaluationContext.Environment(slots, Collections.emptyList());
        Assert.assertEquals("a.id", source(context.resolve(reference)));
        Assert.assertEquals("b.id", source(context.resolve(reference, other)));
        Assert.assertEquals("a.id", source(context.resolve(reference)));
        Expressions.Call count = AnalysisFixtures.call("count", "count", FunctionSymbol.FunctionKind.AGGREGATE);
        RelationEvaluationContext.Environment noRows = context.getEnvironment().withRows(Collections.emptyList());
        Assert.assertSame(context.getEnvironment().getSlots(), noRows.getSlots());
        Assert.assertTrue(hasRows(context.resolve(count)));
        Assert.assertFalse(hasRows(context.resolve(count, noRows)));
        Assert.assertTrue(hasRows(context.resolve(count)));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void evaluatorCannotMutateOwnedEnvironment() {
        RelationEvaluationContext context = new RelationEvaluationContext("1", Collections.emptyMap(), Collections.emptyList(),
                new FactSupport(), (expression, slots, rows) -> {
                    slots.put(new SlotId("illegal"), RelationFactsEngine.facts(expression.getId(), Collections.emptyList()));
                    return null;
                });
        context.resolve(AnalysisFixtures.literal("literal", "1"));
    }

    @Test
    public void directPreAnalyzedEntryPointReportsMissingFacts() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        Relations.Filter filter = filter(scan);
        try {
            new DefaultLineageRuleRegistry().relationRule(LineageRuleIdentity.ir("Filter")).get().analyze(filter,
                    Collections.emptyList(), Collections.emptyMap(), new LineageRuleContext("1", Collections.emptyMap()));
            Assert.fail("Expected missing analyzed expression");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("Missing analyzed expression predicate", expected.getMessage());
        }
    }

    private static RelationFactsEngine engine(ExpressionLineageRule expression, RelationLineageRule relation) {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        return new RelationFactsEngine(AnalysisFixtures.unit(Arrays.asList(scan, filter(scan)), Collections.emptyList()),
                new DefaultLineageRuleRegistry(Collections.singletonMap(LineageRuleIdentity.ir("Literal"), expression),
                        Collections.singletonMap(LineageRuleIdentity.ir("Filter"), relation)));
    }

    private static Relations.Filter filter(Relations.Scan scan) {
        return new Relations.Filter(new NodeId("filter"), scan.getOutputSchema(), new QueryBlockId("main"), 1, null,
                scan.getId(), AnalysisFixtures.literal("predicate", "true"), Collections.emptyList());
    }

    private static String source(ExpressionFacts facts) {
        return ((ColumnEndpoint) facts.getDependencies().get(0).getSource()).getFieldId().getValue();
    }

    private static boolean hasRows(ExpressionFacts facts) {
        return facts.getDependencies().stream().anyMatch(dependency -> dependency.getRole() == DependencyRole.ROWSET);
    }
}
