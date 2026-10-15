package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class RelationRuleRegistryContractTest {
    @Test
    public void everyBuiltInOperatorHasOneRegisteredImplementation() {
        LineageRuleRegistry rules = new DefaultLineageRuleRegistry();
        for (String operation : Arrays.asList("Scan", "Values", "Project", "Filter", "Join", "Aggregate", "SetOp", "Window",
                "Generate", "Sort", "Limit", "Distinct", "DefinitionInstance", "UnknownRelation", "ExternalDatasetRelation")) {
            Assert.assertTrue(operation, rules.relationRule(new RuleKey("ir", operation, "1")).isPresent());
            Assert.assertFalse(operation, rules.relationRule(new RuleKey("ir", operation, "2")).isPresent());
        }
    }

    @Test
    public void projectFallbackAndDefaultRegistryPreserveSameProofIdentities() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        Relations.Project project = new Relations.Project(new NodeId("project"),
                OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("result"))), new QueryBlockId("main"), 1, null,
                scan.getId(), Collections.singletonList(new OutputAssignment(new SlotId("result"), AnalysisFixtures.ref("ref", "scan.id"))));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.asList(scan, project), Collections.emptyList());
        LineageRuleRegistry empty = new LineageRuleRegistry() {
            @Override
            public Optional<ExpressionLineageRule> expressionRule(RuleKey key) {
                return Optional.empty();
            }
            @Override
            public Optional<RelationLineageRule> relationRule(RuleKey key) {
                return Optional.empty();
            }
        };
        RelationFacts registered = new RelationFactsEngine(unit, new DefaultLineageRuleRegistry()).relation(project.getId());
        RelationFacts fallback = new RelationFactsEngine(unit, empty).relation(project.getId());
        Assert.assertEquals(registered.getRowSetFacts().stream().map(value -> value.getEvidence().getId()).collect(Collectors.toList()),
                fallback.getRowSetFacts().stream().map(value -> value.getEvidence().getId()).collect(Collectors.toList()));
        Assert.assertEquals(registered.getOutputFacts().get(new SlotId("result")).getDependencies().get(0).getEvidence().getId(),
                fallback.getOutputFacts().get(new SlotId("result")).getDependencies().get(0).getEvidence().getId());
        Assert.assertEquals("project.rows", registered.getRowSetFacts().get(0).getEvidence().getRuleId());
    }

    @Test
    public void registeredBuiltInsHonorThePublicPreAnalyzedExpressionContract() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        com.panfan.hivelineage.v2.ir.expression.Expressions.Literal condition = AnalysisFixtures.literal("predicate", "true");
        Relations.Filter filter = new Relations.Filter(new NodeId("filter"), scan.getOutputSchema(), new QueryBlockId("main"), 1, null,
                scan.getId(), condition, AnalysisFixtures.passthrough(scan.getOutputSchema()));
        RelationFactsEngine engine = new RelationFactsEngine(AnalysisFixtures.unit(Collections.singletonList(scan), Collections.emptyList()),
                new DefaultLineageRuleRegistry());
        RelationFacts input = engine.relation(scan.getId());
        ExpressionFacts predicate = engine.expression(condition, input.getOutputFacts(), input.getRowSetFacts());
        RelationFacts result = new DefaultLineageRuleRegistry().relationRule(new RuleKey("ir", "Filter", "1")).get()
                .analyze(filter, Collections.singletonList(input), Collections.singletonMap(condition.getId(), predicate),
                        new LineageRuleContext("1", input.getOutputFacts()));
        Assert.assertEquals(1, result.getOutputFacts().size());
        Assert.assertTrue(result.getRowSetFacts().stream().anyMatch(dependency -> dependency.getSource() instanceof RowSetEndpoint));
        Assert.assertTrue(result.getRowSetFacts().stream().anyMatch(dependency -> dependency.getSource() instanceof ConstantEndpoint
                && dependency.getRole() == DependencyRole.CONDITION));
    }

    @Test
    public void customOperatorRuleOverridesBuiltInAndRunsOncePerRelation() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        AtomicInteger visits = new AtomicInteger();
        RelationLineageRule custom = (relation, inputs, expressions, context) -> {
            visits.incrementAndGet();
            Assert.assertEquals("1", context.getRulesVersion());
            return new RelationFacts(relation.getId(), Collections.emptyMap(), Collections.emptyList(),
                    com.panfan.hivelineage.v2.common.DiagnosticBag.empty());
        };
        RelationFactsEngine engine = new RelationFactsEngine(AnalysisFixtures.unit(Collections.singletonList(scan), Collections.emptyList()),
                new DefaultLineageRuleRegistry(Collections.emptyMap(), Collections.singletonMap(new RuleKey("ir", "Scan", "1"), custom)));
        Assert.assertTrue(engine.relation(scan.getId()).getOutputFacts().isEmpty());
        engine.relation(scan.getId());
        Assert.assertEquals(1, visits.get());
    }
}
