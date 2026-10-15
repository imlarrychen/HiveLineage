package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundQueryBlockReference;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.common.QualifiedName;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class DefaultJoinConditionAnalysisTest {
    @Test
    public void selfJoinHandlesKeepBothAliasesAndDeduplicateOnlyPhysicalReferences() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        QualifiedFieldHandle leftField = handle("left.ref", left);
        QualifiedFieldHandle rightField = handle("right.ref", right);
        Expressions.Call predicate = AnalysisFixtures.call("equals", "equal", FunctionSymbol.FunctionKind.OPERATOR, leftField, rightField);
        Relations.Join join = join("join", left, right, predicate, Relations.JoinForm.EXPLICIT, Collections.emptyList());
        JoinFact fact = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, join), Collections.emptyList())).getJoins().get(0);
        assertEquals(2, fact.getDirectReferences().size());
        assertEquals(left.getInstanceId(), ((DirectFieldEndpoint) fact.getDirectReferences().get(0)).getOwner());
        assertEquals(right.getInstanceId(), ((DirectFieldEndpoint) fact.getDirectReferences().get(1)).getOwner());
        assertEquals(1, fact.getPhysicalReferences().size());
        assertSame(predicate, fact.getConditionSource().getPredicate().get());
    }

    @Test
    public void implicitWhereIncludesCrossSourceConditionsAndDropsSingleSourceFilter() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        Expressions.Call cross = AnalysisFixtures.call("cross", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                handle("left.ref", left), handle("right.ref", right));
        Expressions.Call filter = AnalysisFixtures.call("single", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                handle("left.filter", left), AnalysisFixtures.literal("constant", "1"));
        Relations.Join join = join("join", left, right, null, Relations.JoinForm.COMMA, Arrays.asList(
                new Relations.JoinPredicate(cross, Relations.PredicateOrigin.WHERE, Arrays.asList(left.getInstanceId(), right.getInstanceId()), 1),
                new Relations.JoinPredicate(filter, Relations.PredicateOrigin.WHERE, Collections.singletonList(left.getInstanceId()), 2)));
        JoinFact fact = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, join), Collections.emptyList())).getJoins().get(0);
        assertEquals(JoinKind.IMPLICIT, fact.getKind());
        assertSame(cross, fact.getConditionSource().getPredicate().get());
        assertEquals(2, fact.getDirectReferences().size());
    }

    @Test
    public void singleSidedOnStillProducesCompleteConditionAndStableIndex() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        Expressions.Call predicate = AnalysisFixtures.call("single", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                handle("left.ref", left), AnalysisFixtures.literal("constant", "1"));
        Relations.Join join = join("join", left, right, predicate, Relations.JoinForm.EXPLICIT, Collections.emptyList());
        JoinFact fact = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, join), Collections.emptyList())).getJoins().get(0);
        assertEquals(1, fact.getJoinIndex());
        assertEquals(1, fact.getDirectReferences().size());
        assertSame(predicate, fact.getConditionSource().getPredicate().get());
    }

    @Test
    public void declaredPathsAreIndependentOfOpaqueBlockIdsAndHistoricalDefinitionsAreExcluded() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        Expressions.Call predicate = AnalysisFixtures.call("condition", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                handle("left.ref", left), handle("right.ref", right));
        Relations.Join join = join("join", left, right, predicate, Relations.JoinForm.EXPLICIT, Collections.emptyList());
        BoundAnalysisUnit base = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, join), Collections.emptyList());
        Map<QueryBlockId, BoundQueryBlockReference> blocks = new LinkedHashMap<>();
        blocks.put(join.getQueryBlockId(), new BoundQueryBlockReference(join.getQueryBlockId(), "root.where.exists[2]"));
        BoundAnalysisUnit declared = declarations(base, blocks);
        assertEquals("root.where.exists[2]", analyze(declared).getJoins().get(0).getQueryBlockPath());
        blocks.put(join.getQueryBlockId(), new BoundQueryBlockReference(join.getQueryBlockId(), "historic.view", false));
        assertEquals(0, analyze(declarations(base, blocks)).getJoins().size());
    }

    @Test
    public void unresolvedLogicalReferenceRetainsDisplayOnlyEndpointWithoutPhysicalFabrication() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        Expressions.UnknownReference unknown = new Expressions.UnknownReference(new NodeId("unknown.ref"), TypeRef.unknown(), null,
                Collections.emptyList(), Collections.singletonList(left.getInstanceId()), "UNKNOWN_LOGICAL_FIELD", left.getInstanceId(),
                new QualifiedName(Arrays.asList("x", "id")));
        Expressions.Call predicate = AnalysisFixtures.call("condition", "equal", FunctionSymbol.FunctionKind.OPERATOR, unknown, handle("right.ref", right));
        Relations.Join join = join("join", left, right, predicate, Relations.JoinForm.EXPLICIT, Collections.emptyList());
        JoinFact fact = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, join), Collections.emptyList())).getJoins().get(0);
        assertEquals("x.id", ((UnknownDirectEndpoint) fact.getDirectReferences().get(0)).getDisplayName().getDisplayName());
        assertEquals(1, fact.getPhysicalReferences().size());
        assertEquals(1, fact.getDiagnostics().getDiagnostics().size());
    }

    @Test
    public void sameBlockConsecutiveJoinsUseStructuralPostorderEvenWhenSourceOffsetsPreferOuter() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        Relations.Scan third = lightweight("third");
        Expressions.Call firstCondition = AnalysisFixtures.call("first", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                handle("first.left", left), handle("first.right", right));
        Relations.Join first = join("first.join", left, right, firstCondition, Relations.JoinForm.EXPLICIT, Collections.emptyList());
        Expressions.Call secondCondition = AnalysisFixtures.call("second", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                handle("second.right", right), handle("second.third", third));
        Relations.Join second = new Relations.Join(new NodeId("second.join"), OutputSchema.notRequested(), new QueryBlockId("main"), 0, null,
                first.getId(), third.getId(), Relations.JoinType.LEFT, Relations.JoinForm.EXPLICIT, secondCondition, Collections.emptyList(), Collections.emptyList());
        JoinFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, third, second, first), Collections.emptyList()));
        assertEquals(firstCondition.getId(), facts.getJoins().get(0).getPredicateId().get());
        assertEquals(secondCondition.getId(), facts.getJoins().get(1).getPredicateId().get());
    }

    @Test
    public void outerCorrelationIsExcludedFromImplicitWhereButRetainedInExplicitOn() {
        Relations.Scan left = lightweight("left");
        Relations.Scan right = lightweight("right");
        Relations.Scan original = AnalysisFixtures.scan("outer", "outer.object", "outer.block");
        Relations.Scan outer = new Relations.Scan(original.getId(), OutputSchema.notRequested(), original.getQueryBlockId(), 0, null,
                original.getObjectId(), original.getVersion(), original.getInstanceId());
        CorrelatedQualifiedFieldHandle correlated = new CorrelatedQualifiedFieldHandle(new NodeId("outer.ref"), TypeRef.unknown(), null,
                new ScopeId("outer.scope"), outer.getInstanceId(), new FieldIdentity("outer.id", "id"), Collections.emptyList(),
                CorrelatedQualifiedFieldHandle.ExistenceState.UNVERIFIED);
        Expressions.Call predicate = AnalysisFixtures.call("related", "equal", FunctionSymbol.FunctionKind.OPERATOR, handle("left.ref", left), correlated);
        Relations.Join implicit = join("implicit", left, right, null, Relations.JoinForm.COMMA, Collections.singletonList(
                new Relations.JoinPredicate(predicate, Relations.PredicateOrigin.WHERE, Arrays.asList(left.getInstanceId(), outer.getInstanceId()), 1)));
        Map<ScopeId, BoundScopeReference> scopes = new LinkedHashMap<>();
        scopes.put(new ScopeId("outer.scope"), new BoundScopeReference(new ScopeId("outer.scope"), outer.getQueryBlockId(), Optional.empty()));
        scopes.put(new ScopeId("main.scope"), new BoundScopeReference(new ScopeId("main.scope"), left.getQueryBlockId(), Optional.of(new ScopeId("outer.scope"))));
        assertEquals(0, analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(outer, left, right, implicit), Collections.emptyList(), scopes)).getJoins().size());
        Relations.Join explicit = join("explicit", left, right, predicate, Relations.JoinForm.EXPLICIT, Collections.emptyList());
        JoinFact fact = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(outer, left, right, explicit), Collections.emptyList(), scopes)).getJoins().get(0);
        assertSame(predicate, fact.getConditionSource().getPredicate().get());
        assertEquals(2, fact.getDirectReferences().size());
    }

    private BoundAnalysisUnit declarations(BoundAnalysisUnit base, Map<QueryBlockId, BoundQueryBlockReference> blocks) {
        return new BoundAnalysisUnit(base.getStatementId(), base.getRelations(), base.getQueryResults(), base.getWrites(), base.getEffects(),
                base.getBindingCoverage(), base.getDiagnostics(), base.getNotRequestedRegions(), base.getExecutionAssumption(), base.getObjects(),
                base.getScopes(), base.getRelationReferences(), blocks);
    }

    private Relations.Scan lightweight(String id) {
        Relations.Scan scan = AnalysisFixtures.scan(id, "same", "main");
        return new Relations.Scan(scan.getId(), OutputSchema.notRequested(), scan.getQueryBlockId(), 0, null, scan.getObjectId(), scan.getVersion(), scan.getInstanceId());
    }

    private QualifiedFieldHandle handle(String id, Relations.Scan scan) {
        return new QualifiedFieldHandle(new NodeId(id), TypeRef.unknown(), null, scan.getInstanceId(), new FieldIdentity("same.id", "id"),
                Collections.emptyList(), QualifiedFieldHandle.ExistenceState.UNVERIFIED);
    }

    private Relations.Join join(String id, Relations.Scan left, Relations.Scan right, com.panfan.hivelineage.v2.ir.expression.BoundExpression predicate,
                                Relations.JoinForm form, java.util.List<Relations.JoinPredicate> predicates) {
        return new Relations.Join(new NodeId(id), OutputSchema.notRequested(), new QueryBlockId("main"), 10, null,
                left.getId(), right.getId(), Relations.JoinType.INNER, form, predicate, Collections.<SlotMapping>emptyList(), predicates);
    }

    private JoinFacts analyze(BoundAnalysisUnit unit) {
        return new DefaultJoinConditionAnalysis().analyze(AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.CONDITION_FIELDS));
    }
}
