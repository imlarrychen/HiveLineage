package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.analysis.DefaultJoinConditionAnalysis;
import com.panfan.hivelineage.v2.analysis.DefaultLineageEngine;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import com.panfan.hivelineage.v2.metadata.BudgetedMetadataAccess;
import com.panfan.hivelineage.v2.metadata.CapturedMetadataAccess;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.ExternalReadBudget;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataFacet;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.ProviderCapabilities;
import com.panfan.hivelineage.v2.metadata.RelationMetadata;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.BindingOutcome;
import com.panfan.hivelineage.v2.semantic.BindingResult;
import com.panfan.hivelineage.v2.semantic.SemanticBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ResourceLimitsIntegrationTest {
    @Test
    public void irBudgetCountsUniqueExpressionsAndSlotsBeyondRelationCount() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "a", "b");
        Expressions.Call add = AnalysisFixtures.call("add", "add", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("a.ref", "scan.a"), AnalysisFixtures.ref("b.ref", "scan.b"));
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("sum"))),
                new QueryBlockId("main"), 1, null, scan.getId(), Collections.singletonList(new OutputAssignment(new SlotId("sum"), add)));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, project), Collections.emptyList());
        IrResourceGuard guard = new IrResourceGuard();
        assertEquals(8, guard.checkUnit(unit, limits(8, 100)).getNodeCount());
        assertThrows(ResourceLimitException.class, () -> guard.checkUnit(unit, limits(7, 100)));
    }

    @Test
    public void iterativeGuardHandlesLargeSharedDagWithoutRepeatedExpansion() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main");
        List<BoundRelation> relations = new ArrayList<BoundRelation>();
        relations.add(scan);
        NodeId previous = scan.getId();
        for (int index = 0; index < 3000; index++) {
            Relations.SetOp shared = new Relations.SetOp(new NodeId("shared-" + index), OutputSchema.notRequested(), new QueryBlockId("main"), index,
                    null, Arrays.asList(previous, previous), Relations.SetOperator.UNION, Relations.DuplicatePolicy.ALL, Collections.emptyList());
            relations.add(shared);
            previous = shared.getId();
        }
        IrResourceGuard.Measures measure = new IrResourceGuard().checkUnit(AnalysisFixtures.unit(relations, Collections.emptyList()), limits(4000, 4000));
        assertEquals(3001, measure.getNodeCount());
        assertEquals(3001, measure.getMaxDepth());
    }

    @Test
    public void deepFiveHundredNodePlanFailsConfiguredDepthBeforeRecursiveStages() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main");
        List<BoundRelation> relations = new ArrayList<BoundRelation>();
        relations.add(scan);
        NodeId previous = scan.getId();
        for (int index = 0; index < 500; index++) {
            Relations.Project node = new Relations.Project(new NodeId("project-" + index), OutputSchema.notRequested(), new QueryBlockId("main"), index,
                    null, previous, Collections.emptyList());
            relations.add(node);
            previous = node.getId();
        }
        assertThrows(ResourceLimitException.class, () -> new IrResourceGuard().checkUnit(AnalysisFixtures.unit(relations, Collections.emptyList()), limits(1000, 128)));
    }

    @Test
    public void actualSharedDagEngineHandlesDefaultDepthAndReverseDeclarationOrder() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main");
        List<BoundRelation> relations = new ArrayList<BoundRelation>();
        relations.add(scan);
        NodeId previous = scan.getId();
        for (int index = 0; index < 500; index++) {
            Relations.SetOp shared = new Relations.SetOp(new NodeId("shared-" + index), OutputSchema.notRequested(), new QueryBlockId("main"), index,
                    null, Arrays.asList(previous, previous), Relations.SetOperator.UNION, Relations.DuplicatePolicy.ALL, Collections.emptyList());
            relations.add(shared);
            previous = shared.getId();
        }
        Collections.reverse(relations);
        BoundAnalysisUnit unit = AnalysisFixtures.unit(relations, Collections.emptyList());
        new IrResourceGuard().checkUnit(unit, limits(1000, 512));
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS));
        assertEquals(501, facts.getRelations().size());
        assertEquals(1, facts.getRelations().get(0).getRowSetFacts().size());
        assertTrue(facts.getEvidenceGraph().getEvidence().size() <= 1001);
    }

    @Test
    public void evidenceLimitIncludesRelationAndOutputEdgesWhenThereAreNoWrites() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(scan), Collections.emptyList());
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA));
        assertTrue(facts.getWriteDependencies().isEmpty());
        assertThrows(ResourceLimitException.class, () -> new IrResourceGuard().checkFacts(facts, new ResourceLimits(1000, 64, 1000, 1, 100)));
    }

    @Test
    public void defaultAnalysisFactoriesStopImmediatelyWhenProofBudgetIsZero() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "main", "id");
        Relations.Scan right = AnalysisFixtures.scan("right", "b", "main", "id");
        Expressions.Call condition = AnalysisFixtures.call("condition", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("left.ref", "left.id"), AnalysisFixtures.ref("right.ref", "right.id"));
        Relations.Join join = new Relations.Join(new NodeId("join"), OutputSchema.known(Collections.emptyList()), new QueryBlockId("main"), 1, null,
                left.getId(), right.getId(), Relations.JoinType.INNER, Relations.JoinForm.EXPLICIT, condition, Collections.emptyList(), Collections.emptyList());
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, join), Collections.emptyList());
        VerifiedAnalysisUnit verified = AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.CONDITION_FIELDS);
        assertThrows(ResourceLimitException.class, () -> new DefaultLineageEngine(0).analyze(verified));
        assertThrows(ResourceLimitException.class, () -> new DefaultJoinConditionAnalysis(0).analyze(verified));
    }

    @Test
    public void bundledMetadataReadCostsOneAndNewRequestsDoNotInheritBatchCounts() {
        AtomicInteger calls = new AtomicInteger();
        MetadataReadContext context = new MetadataReadContext("source", "cluster", Optional.empty(), Collections.emptyMap());
        WorkflowContext workflow = workflow(context, calls);
        CapturedMetadataAccess captured = new DefaultMetadataAccessFactory().create(workflow);
        ExternalReadBudget firstBudget = new ExternalReadBudget(1);
        BudgetedMetadataAccess first = new BudgetedMetadataAccess(captured, firstBudget);
        QualifiedName a = new QualifiedName(Arrays.asList("db", "a"));
        first.lookupObject(a, context);
        first.lookupSchema(a, context);
        assertEquals(1, calls.get());
        assertEquals(1, firstBudget.getConsumed());
        assertTrue(captured.manifest().getRecords().size() > 1);
        assertThrows(ResourceLimitException.class, () -> first.lookupRelation(new QualifiedName(Arrays.asList("db", "b")), context));
        assertEquals(1, calls.get());
        ExternalReadBudget cachedBudget = new ExternalReadBudget(0);
        new BudgetedMetadataAccess(captured, cachedBudget).lookupSchema(a, context);
        assertEquals(0, cachedBudget.getConsumed());
        ExternalReadBudget nextBudget = new ExternalReadBudget(1);
        new BudgetedMetadataAccess(captured, nextBudget).lookupRelation(new QualifiedName(Arrays.asList("db", "b")), context);
        assertEquals(1, nextBudget.getConsumed());
        assertEquals(2, calls.get());
    }

    @Test
    public void zeroExternalReadBudgetRejectsBeforeProviderAndDoesNotPoisonPinnedResults() {
        AtomicInteger calls = new AtomicInteger();
        MetadataReadContext context = new MetadataReadContext("source", "cluster", Optional.empty(), Collections.emptyMap());
        CapturedMetadataAccess captured = new DefaultMetadataAccessFactory().create(workflow(context, calls));
        QualifiedName table = new QualifiedName(Arrays.asList("db", "table"));
        assertThrows(ResourceLimitException.class, () -> new BudgetedMetadataAccess(captured, new ExternalReadBudget(0)).lookupRelation(table, context));
        assertEquals(0, calls.get());
        assertTrue(captured.snapshot().getLookups().isEmpty());
        new BudgetedMetadataAccess(captured, new ExternalReadBudget(1)).lookupRelation(table, context);
        assertEquals(1, calls.get());
    }

    @Test
    public void scriptAndExpandedStatementLimitsStopBeforeFrontendAndProvider() {
        AtomicInteger parseCalls = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        SyntaxFrontend frontend = (unit, dialect) -> {
            parseCalls.incrementAndGet();
            throw new AssertionError("Frontend must not run after resource exhaustion");
        };
        SemanticBinder binder = (syntax, context, demand) -> new BindingResult(BindingOutcome.FAILED, Optional.empty(), Collections.emptyList(), DiagnosticBag.empty());
        DefaultScriptLineageAnalyzer analyzer = new DefaultScriptLineageAnalyzer(frontend, binder, new DefaultMetadataAccessFactory());
        ScriptAnalysisRequest tooLong = request("SELECT 123456789;", Collections.emptyMap(), new ResourceLimits(5, 64, 1000, 1000, 100));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyzeScript(tooLong, workflow(tooLong.getMetadataContext(), providerCalls)));
        ScriptAnalysisRequest expanded = request("SELECT '${hivevar:x}';", Collections.singletonMap("hivevar:x", String.join("", Collections.nCopies(100, "x"))),
                new ResourceLimits(30, 64, 1000, 1000, 100));
        ScriptAnalysisResult result = analyzer.analyzeScript(expanded, workflow(expanded.getMetadataContext(), providerCalls));
        assertEquals(StatementStatus.FAILED, result.getStatements().get(0).getStatus());
        assertEquals("RESOURCE_LIMIT", result.getStatements().get(0).getFailureCode().get());
        assertEquals(0, parseCalls.get());
        assertEquals(0, providerCalls.get());
    }

    private ResourceLimits limits(int nodes, int depth) {
        return new ResourceLimits(100_000, 64, nodes, 100_000, 1000, depth);
    }

    private ScriptAnalysisRequest request(String script, java.util.Map<String, String> variables, ResourceLimits limits) {
        ScriptAnalysisRequest base = new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder().workflowId("workflow").taskId("task").taskVersion("1")
                .defaultDatabase("default").rawScript(script).runtimeVariables(variables).build());
        return new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(), base.getMetadataContext(), base.getContract(), limits);
    }

    private WorkflowContext workflow(MetadataReadContext context, AtomicInteger calls) {
        MetadataProvider provider = new MetadataProvider() {
            @Override
            public ProviderCapabilities getCapabilities() {
                return new ProviderCapabilities(EnumSet.allOf(MetadataFacet.class), ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, false);
            }

            @Override
            public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext reads) {
                calls.incrementAndGet();
                MetadataProvenance provenance = new MetadataProvenance("provider", MetadataProvenance.Origin.TEST_FIXTURE, Optional.empty(), Optional.empty());
                ObjectDescriptor object = new ObjectDescriptor(new ObjectId("opaque:" + name.getDisplayName()), name, ObjectDescriptor.Kind.TABLE, Optional.empty(), provenance, MetadataVersion.unknown());
                return LookupResult.found(new RelationMetadata(object, new RelationSchema(SchemaState.KNOWN, Collections.emptyList(), provenance, MetadataVersion.unknown())), DiagnosticBag.empty());
            }
        };
        return new WorkflowContext("workflow", context, provider, new DefaultMetadataCache());
    }
}
