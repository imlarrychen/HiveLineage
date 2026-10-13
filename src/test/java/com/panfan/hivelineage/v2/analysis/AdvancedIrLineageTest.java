package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.ResolvedFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdvancedIrLineageTest {
    @Test
    public void singleConstantRowDoesNotCreateUnknownOrInventPhysicalSources() {
        Relations.Values input = new Relations.Values(new NodeId("one-row"), OutputSchema.known(Collections.emptyList()),
                new QueryBlockId("main"), 0, null, Collections.singletonList(Collections.emptyList()));
        Relations.Project project = new Relations.Project(new NodeId("constant"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("one"))),
                new QueryBlockId("main"), 1, null, input.getId(), Collections.singletonList(new OutputAssignment(new SlotId("one"), AnalysisFixtures.literal("literal", "1"))));
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(input, project), Collections.emptyList()), BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        assertEquals(Coverage.COMPLETE, facts.getQuality().getCoverage());
        assertTrue(facts.getDiagnostics().getDiagnostics().isEmpty());
        assertTrue(relation(facts, "constant").getRowSetFacts().isEmpty());
        assertTrue(relation(facts, "constant").getOutputFacts().get(new SlotId("one")).getDependencies().get(0).getSource() instanceof ConstantEndpoint);
    }

    @Test
    public void multiOutputOuterGeneratorSharesKnownArgumentSourcesAndRows() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "map");
        Relations.Generate generate = new Relations.Generate(new NodeId("generate"), OutputSchema.known(Arrays.asList(
                AnalysisFixtures.output("key"), AnalysisFixtures.output("value"))), new QueryBlockId("main"), 1, null, scan.getId(),
                new ResolvedFunctionSymbol(new QualifiedName(Collections.singletonList("explode")), FunctionSymbol.FunctionKind.GENERATOR, "generator.explode"),
                Collections.singletonList(AnalysisFixtures.ref("map.ref", "scan.map")), Arrays.asList(new SlotId("key"), new SlotId("value")), Collections.emptyList(), true);
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, generate), Collections.emptyList()), BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        RelationFacts output = relation(facts, "generate");
        assertEquals(2, output.getOutputFacts().size());
        for (ExpressionFacts value : output.getOutputFacts().values()) {
            assertTrue(value.getDependencies().stream().anyMatch(item -> item.getSource() instanceof ColumnEndpoint
                    && ((ColumnEndpoint) item.getSource()).getFieldId().getValue().equals("orders.map")));
            assertTrue(value.getDependencies().stream().anyMatch(item -> item.getSource() instanceof RowSetEndpoint));
        }
    }

    @Test
    public void correlatedExistsPropagatesBothInnerAndOuterObjectsAndPredicateInfluence() {
        Relations.Scan outer = AnalysisFixtures.scan("outer", "a", "main", "id");
        Relations.Scan inner = AnalysisFixtures.scan("inner", "b", "sub", "id");
        Expressions.CorrelatedSlotRef correlated = new Expressions.CorrelatedSlotRef(new NodeId("outer.ref"), TypeRef.unknown(), null,
                new ScopeId("main.scope"), new SlotId("outer.id"), Collections.emptyList());
        Expressions.Call condition = AnalysisFixtures.call("sub.condition", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("inner.ref", "inner.id"), correlated);
        Relations.Filter filter = new Relations.Filter(new NodeId("inner.filter"), inner.getOutputSchema(), new QueryBlockId("sub"), 1, null,
                inner.getId(), condition, AnalysisFixtures.passthrough(inner.getOutputSchema()));
        Expressions.ExistsSubquery exists = new Expressions.ExistsSubquery(new NodeId("exists"), TypeRef.unknown(), null, filter.getId(), false);
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("exists.out"))),
                new QueryBlockId("main"), 2, null, outer.getId(), Collections.singletonList(new OutputAssignment(new SlotId("exists.out"), exists)));
        Map<ScopeId, BoundScopeReference> scopes = new LinkedHashMap<ScopeId, BoundScopeReference>();
        scopes.put(new ScopeId("main.scope"), new BoundScopeReference(new ScopeId("main.scope"), new QueryBlockId("main"), Optional.empty()));
        scopes.put(new ScopeId("sub.scope"), new BoundScopeReference(new ScopeId("sub.scope"), new QueryBlockId("sub"), Optional.of(new ScopeId("main.scope"))));
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(outer, inner, filter, project), Collections.emptyList(), scopes),
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        List<Dependency> output = relation(facts, "project").getOutputFacts().get(new SlotId("exists.out")).getDependencies();
        assertTrue(output.stream().anyMatch(item -> item.getSource() instanceof ColumnEndpoint
                && ((ColumnEndpoint) item.getSource()).getFieldId().getValue().equals("a.id")));
        assertTrue(output.stream().anyMatch(item -> item.getSource() instanceof ColumnEndpoint
                && ((ColumnEndpoint) item.getSource()).getFieldId().getValue().equals("b.id")));
        assertTrue(output.stream().anyMatch(item -> item.getSource() instanceof RowSetEndpoint));
    }

    @Test
    public void expandedViewPreservesPhysicalValueAndIndependentDefinitionBoundary() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "base", "view.query", "id");
        Relations.DefinitionInstance view = new Relations.DefinitionInstance(new NodeId("view"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("view.id"))),
                new QueryBlockId("main"), 1, null, scan.getId(), new DefinitionId("view.definition"), new RelationInstanceId("view.instance"),
                VersionRef.notRequested(), Collections.singletonList(new SlotMapping(new SlotId("view.id"), new SlotId("scan.id"))), new ObjectId("view.object"));
        BoundAnalysisUnit base = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, view), Collections.emptyList());
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<ObjectId, BoundObjectReference>(base.getObjects());
        objects.put(new ObjectId("view.object"), new BoundObjectReference(new ObjectId("view.object"), new QualifiedName(Arrays.asList("db", "view")),
                BoundObjectReference.ObjectKind.VIEW));
        BoundAnalysisUnit unit = new BoundAnalysisUnit(base.getStatementId(), base.getRelations(), base.getQueryResults(), base.getWrites(), base.getEffects(),
                base.getBindingCoverage(), base.getDiagnostics(), base.getNotRequestedRegions(), base.getExecutionAssumption(), objects, base.getScopes());
        List<Dependency> output = relation(analyze(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA), "view").getOutputFacts().get(new SlotId("view.id")).getDependencies();
        assertTrue(output.stream().anyMatch(item -> item.getRole() == DependencyRole.VALUE && item.getSource() instanceof ColumnEndpoint
                && ((ColumnEndpoint) item.getSource()).getObjectId().equals(new ObjectId("base"))));
        assertTrue(output.stream().anyMatch(item -> item.getRole() == DependencyRole.DEFINITION && item.getSource() instanceof ColumnEndpoint
                && ((ColumnEndpoint) item.getSource()).getObjectId().equals(new ObjectId("view.object"))));
    }

    @Test
    public void objectOnlySubqueryRegionKeepsTablesWithoutClaimingFieldAnalysisOrPartialResult() {
        Relations.Scan outer = lightweight("outer", "a", "main");
        Relations.Scan inner = lightweight("inner", "b", "sub");
        Expressions.NotRequestedExpression condition = new Expressions.NotRequestedExpression(new NodeId("condition"), TypeRef.unknown(), null,
                BindingRequirement.CONDITION_FIELDS, Collections.singletonList(inner.getId()));
        Relations.Filter filter = new Relations.Filter(new NodeId("filter"), OutputSchema.notRequested(), new QueryBlockId("main"), 1, null,
                outer.getId(), condition, Collections.emptyList());
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(outer, inner, filter), Collections.emptyList()), BindingRequirement.OBJECTS);
        assertEquals(Coverage.COMPLETE, facts.getQuality().getCoverage());
        assertTrue(facts.getDiagnostics().getDiagnostics().isEmpty());
        List<Dependency> rows = relation(facts, "filter").getRowSetFacts();
        assertTrue(rows.stream().anyMatch(item -> item.getSource() instanceof RowSetEndpoint
                && ((RowSetEndpoint) item.getSource()).getRelationId().equals(inner.getInstanceId())));
    }

    @Test
    public void correlatedComputedSlotUsesItsDefinitionDespiteReverseDeclarationOrder() {
        Relations.Scan outer = AnalysisFixtures.scan("outer", "a", "main", "id");
        Relations.Scan inner = AnalysisFixtures.scan("inner", "b", "sub", "id");
        Relations.Aggregate aggregate = new Relations.Aggregate(new NodeId("aggregate"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("count"))),
                new QueryBlockId("main"), 1, null, outer.getId(), Collections.emptyList(), Collections.singletonList(new OutputAssignment(new SlotId("count"),
                    AnalysisFixtures.call("count.call", "count", FunctionSymbol.FunctionKind.AGGREGATE))));
        Expressions.CorrelatedSlotRef correlated = new Expressions.CorrelatedSlotRef(new NodeId("outer.count"), TypeRef.unknown(), null,
                new ScopeId("main.scope"), new SlotId("count"), Collections.emptyList());
        Relations.Filter innerFilter = new Relations.Filter(new NodeId("inner.filter"), inner.getOutputSchema(), new QueryBlockId("sub"), 1, null,
                inner.getId(), AnalysisFixtures.call("inner.condition", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                    AnalysisFixtures.ref("inner.id.ref", "inner.id"), correlated), AnalysisFixtures.passthrough(inner.getOutputSchema()));
        Relations.Filter outerFilter = new Relations.Filter(new NodeId("outer.filter"), aggregate.getOutputSchema(), new QueryBlockId("main"), 2, null,
                aggregate.getId(), new Expressions.ExistsSubquery(new NodeId("outer.exists"), TypeRef.unknown(), null, innerFilter.getId(), false),
                AnalysisFixtures.passthrough(aggregate.getOutputSchema()));
        Map<ScopeId, BoundScopeReference> scopes = new LinkedHashMap<ScopeId, BoundScopeReference>();
        scopes.put(new ScopeId("main.scope"), new BoundScopeReference(new ScopeId("main.scope"), new QueryBlockId("main"), Optional.empty()));
        scopes.put(new ScopeId("sub.scope"), new BoundScopeReference(new ScopeId("sub.scope"), new QueryBlockId("sub"), Optional.of(new ScopeId("main.scope"))));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(innerFilter, outerFilter, aggregate, inner, outer), Collections.emptyList(), scopes);
        assertEquals(aggregate.getId(), new PureIrIndex(unit).slotOwner(new SlotId("count")).get().getId());
        LineageFacts facts = analyze(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        assertTrue(relation(facts, "inner.filter").getRowSetFacts().stream().anyMatch(value -> value.getSource() instanceof RowSetEndpoint
                && ((RowSetEndpoint) value.getSource()).getRelationId().equals(outer.getInstanceId())));
    }

    @Test
    public void unrequestedInputWidthDoesNotBecomeUnknownForKnownSelectedFields() {
        Relations.Scan unrequested = lightweight("left", "a", "main");
        Relations.Scan known = AnalysisFixtures.scan("right", "b", "main", "id");
        OutputSchema partialRequest = new OutputSchema(SchemaState.UNKNOWN, Collections.singletonList(
                new KnownOutputSegment(Collections.singletonList(AnalysisFixtures.output("joined.id")))));
        Relations.Join join = new Relations.Join(new NodeId("join"), partialRequest, new QueryBlockId("main"), 1, null,
                unrequested.getId(), known.getId(), Relations.JoinType.CROSS, Relations.JoinForm.EXPLICIT, null,
                Collections.singletonList(new SlotMapping(new SlotId("joined.id"), new SlotId("right.id"))), Collections.emptyList());
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 2, null, join.getId(), Collections.singletonList(new OutputAssignment(new SlotId("out"), AnalysisFixtures.ref("selected", "joined.id"))));
        LineageFacts result = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(unrequested, known, join, project), Collections.emptyList()),
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        assertEquals(Coverage.COMPLETE, result.getQuality().getCoverage());
        assertTrue(result.getDiagnostics().getDiagnostics().isEmpty());
        Relations.Join genuineUnknown = new Relations.Join(new NodeId("unknown.join"), new OutputSchema(SchemaState.UNKNOWN, Arrays.asList(
                partialRequest.getSegments().get(0), new UnknownOutputSegment(new NodeId("gap"), "REQUESTED_SCHEMA_UNKNOWN"))), new QueryBlockId("main"), 1, null,
                unrequested.getId(), known.getId(), Relations.JoinType.CROSS, Relations.JoinForm.EXPLICIT, null,
                Collections.singletonList(new SlotMapping(new SlotId("joined.id"), new SlotId("right.id"))), Collections.emptyList());
        LineageFacts unknown = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(unrequested, known, genuineUnknown), Collections.emptyList()), BindingRequirement.OBJECTS);
        assertEquals(Coverage.PARTIAL, unknown.getQuality().getCoverage());
    }

    private Relations.Scan lightweight(String id, String object, String block) {
        Relations.Scan scan = AnalysisFixtures.scan(id, object, block);
        return new Relations.Scan(scan.getId(), OutputSchema.notRequested(), scan.getQueryBlockId(), 0, null, scan.getObjectId(), scan.getVersion(), scan.getInstanceId());
    }

    private LineageFacts analyze(BoundAnalysisUnit unit, BindingRequirement... requirements) {
        return new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit, requirements));
    }

    private RelationFacts relation(LineageFacts facts, String id) {
        return facts.getRelations().stream().filter(item -> item.getRelationId().equals(new NodeId(id))).findFirst().get();
    }
}
