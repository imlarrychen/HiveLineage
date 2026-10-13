package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.EvidenceId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.ir.statement.VerificationCertificate;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EvidenceGraphTest {
    @Test
    public void everyInternalExpressionParentIsRetainedInCompleteNativeProof() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "main", "id");
        Relations.Scan right = AnalysisFixtures.scan("right", "b", "main", "id");
        Expressions.Call condition = AnalysisFixtures.call("equals", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("left.ref", "left.id"), AnalysisFixtures.ref("right.ref", "right.id"));
        Relations.Join join = new Relations.Join(new NodeId("join"), OutputSchema.known(Arrays.asList(
                AnalysisFixtures.output("joined.left"), AnalysisFixtures.output("joined.right"))), new QueryBlockId("main"), 1, null,
                left.getId(), right.getId(), Relations.JoinType.INNER, Relations.JoinForm.EXPLICIT, condition,
                Arrays.asList(new SlotMapping(new SlotId("joined.left"), new SlotId("left.id")),
                    new SlotMapping(new SlotId("joined.right"), new SlotId("right.id"))), Collections.emptyList());
        Expressions.Call add = AnalysisFixtures.call("add", "add", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("left.value", "joined.left"), AnalysisFixtures.literal("one", "1"));
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 2, null, join.getId(), Collections.singletonList(new OutputAssignment(new SlotId("out"), add)));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(project, join, left, right), Collections.emptyList());
        VerifiedAnalysisUnit verified = AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA, BindingRequirement.CONDITION_FIELDS);
        LineageFacts lineage = new DefaultLineageEngine().analyze(verified);
        JoinFacts joins = new DefaultJoinConditionAnalysis().analyze(verified);
        assertClosed(lineage.getEvidenceGraph());
        assertClosed(joins.getEvidenceGraph());
        assertTrue(lineage.getEvidenceGraph().getEvidence().values().stream().anyMatch(value -> value.getOperationId().equals(add.getId())));
        for (Map.Entry<EvidenceId, Dependency> proof : joins.getEvidenceGraph().getDependencies().entrySet()) {
            Dependency matching = lineage.getEvidenceGraph().getDependencies().get(proof.getKey());
            if (matching != null) {
                assertTrue(FactSupport.sameProof(matching, proof.getValue()));
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> lineage.getEvidenceGraph().getDependencies().clear());
    }

    @Test
    public void independentRuleCountersCannotGiveDifferentProofsTheSameIdentity() {
        NodeId node = new NodeId("shared-operation");
        SlotEndpoint target = new SlotEndpoint(new SlotId("out"));
        Dependency first = new FactSupport().dependency(new ConstantEndpoint("first"), target, DependencyRole.VALUE,
                DependencyPrecision.STRUCTURAL, node, "rule", Collections.emptyList(), Optional.empty());
        Dependency second = new FactSupport().dependency(new ConstantEndpoint("second"), target, DependencyRole.VALUE,
                DependencyPrecision.STRUCTURAL, node, "rule", Collections.emptyList(), Optional.empty());
        Dependency repeated = new FactSupport().dependency(new ConstantEndpoint("first"), target, DependencyRole.VALUE,
                DependencyPrecision.STRUCTURAL, node, "rule", Collections.emptyList(), Optional.empty());
        assertNotEquals(first.getEvidence().getId(), second.getEvidence().getId());
        assertEquals(first.getEvidence().getId(), repeated.getEvidence().getId());
    }

    @Test
    public void incompleteOrCyclicGraphCannotClaimCompleteProof() {
        Dependency missing = proof("child", "absent");
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGraph(Collections.singletonMap(missing.getEvidence().getId(), missing)));
        Dependency cycle = proof("self", "self");
        assertThrows(IllegalArgumentException.class, () -> new EvidenceGraph(Collections.singletonMap(cycle.getEvidence().getId(), cycle)));
    }

    @Test
    public void customRulesRetainIntermediateProofsThroughTheExplicitContextFactory() {
        ExpressionLineageRule custom = (expression, arguments, context) -> {
            SlotEndpoint target = new SlotEndpoint(new SlotId("expression:" + expression.getId().getValue()));
            Dependency intermediate = context.getEvidenceFactory().dependency(new ConstantEndpoint("custom"), target,
                    DependencyRole.VALUE, DependencyPrecision.STRUCTURAL, expression.getId(), "custom.input", Collections.emptyList(), Optional.empty());
            Dependency output = context.getEvidenceFactory().dependency(intermediate.getSource(), target, DependencyRole.VALUE,
                    DependencyPrecision.STRUCTURAL, expression.getId(), "custom.result", Collections.singletonList(intermediate.getEvidence().getId()), Optional.empty());
            return RelationFactsEngine.facts(expression.getId(), Collections.singletonList(output));
        };
        LineageFacts result = analyzeCustomLiteral(custom);
        assertClosed(result.getEvidenceGraph());
        assertTrue(result.getEvidenceGraph().getEvidence().values().stream().anyMatch(value -> value.getRuleId().equals("custom.input")));
    }

    @Test
    public void customRuleThatOmitsItsIntermediateParentIsRejectedExplicitly() {
        ExpressionLineageRule custom = (expression, arguments, context) -> RelationFactsEngine.facts(expression.getId(),
                Collections.singletonList(proof("custom-child", "unretained-parent")));
        assertThrows(IllegalArgumentException.class, () -> analyzeCustomLiteral(custom));
    }

    @Test
    public void factoryRejectsAnEdgeBeforeRetainingProofBeyondItsBudget() {
        FactSupport factory = new FactSupport(1);
        Dependency first = factory.dependency(new ConstantEndpoint("one"), new SlotEndpoint(new SlotId("first")), DependencyRole.VALUE,
                DependencyPrecision.STRUCTURAL, new NodeId("first"), "literal", Collections.emptyList(), Optional.empty());
        assertThrows(ResourceLimitException.class, () -> factory.retarget(Collections.singletonList(first), new SlotEndpoint(new SlotId("second")),
                new NodeId("second"), "project", null, Optional.empty()));
        assertEquals(1, factory.graph().getEvidence().size());
        assertEquals(1, factory.proofEdges());
        assertThrows(ResourceLimitException.class, () -> new FactSupport(0).retain(first));
    }

    @Test
    public void wholeUnitConsumersRejectANarrowRegionCertificate() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main");
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(scan), Collections.emptyList());
        VerificationCertificate certificate = new VerificationCertificate(unit, "region-test", EnumSet.of(BindingRequirement.OBJECTS,
                BindingRequirement.CONDITION_FIELDS), EnumSet.allOf(VerificationCertificate.VerificationCheck.class), Collections.singleton(scan.getId()));
        VerifiedAnalysisUnit narrow = new VerifiedAnalysisUnit(unit, certificate);
        assertEquals(Collections.singleton(scan.getId()), certificate.getRegions());
        assertThrows(IllegalArgumentException.class, () -> new DefaultLineageEngine().analyze(narrow));
        assertThrows(IllegalArgumentException.class, () -> new DefaultJoinConditionAnalysis().analyze(narrow));
    }

    private LineageFacts analyzeCustomLiteral(ExpressionLineageRule custom) {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main");
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 1, null, scan.getId(), Collections.singletonList(new OutputAssignment(new SlotId("out"), AnalysisFixtures.literal("literal", "1"))));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, project), Collections.emptyList());
        DefaultLineageRuleRegistry registry = new DefaultLineageRuleRegistry(Collections.singletonMap(new RuleKey("ir", "Literal", "1"), custom), Collections.emptyMap());
        return new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA), registry);
    }

    private Dependency proof(String id, String parent) {
        Evidence evidence = new Evidence(new EvidenceId(id), new NodeId("operation"), "rule", Collections.singletonList(new EvidenceId(parent)), Optional.empty());
        return new Dependency(new ConstantEndpoint("value"), new SlotEndpoint(new SlotId("out")), DependencyRole.VALUE,
                DependencyPrecision.STRUCTURAL, Optional.empty(), evidence);
    }

    private void assertClosed(EvidenceGraph graph) {
        assertTrue(graph.getEvidence().size() > 2);
        for (Evidence proof : graph.getEvidence().values()) {
            for (EvidenceId parent : proof.getParentEvidence()) {
                assertTrue(graph.getEvidence().containsKey(parent));
            }
        }
    }
}
