package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.expression.OpaqueFunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DefaultLineageEngineTest {
    @Test
    public void filterAndCaseKeepConditionsSeparateFromWriteValueSources() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "price", "qty", "status");
        Expressions.Call predicate = AnalysisFixtures.call("filter.condition", "equal", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("status.filter", "scan.status"), AnalysisFixtures.literal("paid", "paid"));
        Relations.Filter filter = new Relations.Filter(new NodeId("filter"), scan.getOutputSchema(), new QueryBlockId("main"), 1, null,
                scan.getId(), predicate, AnalysisFixtures.passthrough(scan.getOutputSchema()));
        Expressions.Call amount = AnalysisFixtures.call("multiply", "multiply", FunctionSymbol.FunctionKind.OPERATOR,
                AnalysisFixtures.ref("price.ref", "scan.price"), AnalysisFixtures.ref("qty.ref", "scan.qty"));
        Expressions.Case choice = new Expressions.Case(new NodeId("case"), TypeRef.unknown(), null,
                Collections.singletonList(new Expressions.WhenThen(AnalysisFixtures.ref("status.case", "scan.status"), amount)),
                AnalysisFixtures.literal("zero", "0"));
        Relations.Project project = project("project", filter.getId(), "amount", choice);
        BoundWrite write = write(project, "amount");
        // The graph list need not be topologically ordered.
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(project, filter, scan), Collections.singletonList(write)));
        assertTrue(hasColumn(facts.getWriteDependencies(), "orders.price", DependencyRole.VALUE));
        assertTrue(hasColumn(facts.getWriteDependencies(), "orders.qty", DependencyRole.VALUE));
        assertTrue(hasColumn(facts.getWriteDependencies(), "orders.status", DependencyRole.CONDITION));
        assertFalse(hasColumn(facts.getWriteDependencies(), "orders.status", DependencyRole.VALUE));
        assertTrue(relation(facts, "project").getRowSetFacts().stream().anyMatch(item -> item.getSource() instanceof RowSetEndpoint));
    }

    @Test
    public void countStarAndConstantProjectionRetainRowsWithoutInventingColumnValueSource() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "id");
        Expressions.Call count = AnalysisFixtures.call("count", "count", FunctionSymbol.FunctionKind.AGGREGATE);
        Relations.Aggregate aggregate = new Relations.Aggregate(new NodeId("aggregate"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("count.out"))),
                new QueryBlockId("main"), 1, null, scan.getId(), Collections.emptyList(),
                Collections.singletonList(new OutputAssignment(new SlotId("count.out"), count)));
        Relations.Project constant = project("constant", scan.getId(), "one", AnalysisFixtures.literal("one.value", "1"));
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, aggregate, constant), Collections.emptyList()));
        List<Dependency> countFacts = relation(facts, "aggregate").getOutputFacts().get(new SlotId("count.out")).getDependencies();
        assertTrue(countFacts.stream().anyMatch(item -> item.getRole() == DependencyRole.ROWSET && item.getSource() instanceof RowSetEndpoint));
        assertFalse(countFacts.stream().anyMatch(item -> item.getSource() instanceof ColumnEndpoint));
        assertTrue(relation(facts, "constant").getOutputFacts().get(new SlotId("one")).getDependencies().get(0).getSource() instanceof ConstantEndpoint);
        assertFalse(relation(facts, "constant").getRowSetFacts().isEmpty());
    }

    @Test
    public void opaqueFunctionPreservesKnownArgumentAndExplicitExternalUnknown() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "id");
        Expressions.Call udf = new Expressions.Call(new NodeId("udf"), TypeRef.unknown(), null,
                new OpaqueFunctionSymbol(new QualifiedName(Collections.singletonList("custom_udf")), "OPAQUE_FUNCTION"),
                Collections.singletonList(AnalysisFixtures.ref("input", "scan.id")), false);
        Relations.Project project = project("project", scan.getId(), "out", udf);
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, project), Collections.emptyList()));
        List<Dependency> output = relation(facts, "project").getOutputFacts().get(new SlotId("out")).getDependencies();
        assertTrue(hasColumn(output, "orders.id", DependencyRole.VALUE));
        assertTrue(output.stream().anyMatch(item -> item.getSource() instanceof UnknownEndpoint));
        assertEquals(Coverage.PARTIAL, facts.getQuality().getCoverage());
        assertEquals(DependencyPrecision.UNKNOWN, facts.getQuality().getPrecision());
        assertEquals(Validity.UNDETERMINED, facts.getQuality().getValidity());
    }

    @Test
    public void assumedTargetMappingKeepsKnownSourcesWithoutClaimingSqlValidity() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "id");
        Relations.Project project = project("project", scan.getId(), "out", AnalysisFixtures.ref("input", "scan.id"));
        BoundWrite original = write(project, "out");
        BoundWrite assumed = new BoundWrite(original.getId(), original.getTargetObject(), original.getBeforeVersion(), original.getAfterVersion(),
                original.getWriteMode(), original.getAssignments(), original.getPartitionScope(), null, MappingCoverage.PARTIAL,
                Collections.singletonList(new UnknownMapping(new NodeId("mapping"), UnknownMapping.Reason.UNSUPPORTED_RULE, "ASSUMED_TARGET_MAPPING")), project.getId());
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, project), Collections.singletonList(assumed)));
        assertTrue(hasColumn(facts.getWriteDependencies(), "orders.id", DependencyRole.VALUE));
        assertEquals(Coverage.PARTIAL, facts.getQuality().getCoverage());
        assertEquals(Validity.UNDETERMINED, facts.getQuality().getValidity());
    }

    @Test
    public void ambiguousCandidatesAreNotReportedAsProvenValueSources() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "id", "other");
        Expressions.UnknownReference ambiguous = new Expressions.UnknownReference(new NodeId("ambiguous"), TypeRef.unknown(), null,
                Arrays.asList(new SlotId("scan.id"), new SlotId("scan.other")), Collections.singletonList(scan.getInstanceId()), "AMBIGUOUS_COLUMN");
        Relations.Project project = project("project", scan.getId(), "out", ambiguous);
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, project), Collections.emptyList()));
        List<Dependency> output = relation(facts, "project").getOutputFacts().get(new SlotId("out")).getDependencies();
        assertTrue(output.stream().anyMatch(item -> item.getSource() instanceof UnknownEndpoint));
        assertFalse(output.stream().anyMatch(item -> item.getSource() instanceof ColumnEndpoint));
    }

    @Test
    public void windowPartitionAndOrderTagsSurviveProjectAndWriteWithoutBecomingFilterValues() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "orders", "main", "dept", "price");
        Expressions.Call rowNumber = AnalysisFixtures.call("row_number", "row_number", FunctionSymbol.FunctionKind.WINDOW);
        List<OutputSlot> slots = new ArrayList<OutputSlot>(((com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment) scan.getOutputSchema().getSegments().get(0)).getSlots());
        slots.add(AnalysisFixtures.output("rank"));
        Relations.WindowComputation computation = new Relations.WindowComputation(new OutputAssignment(new SlotId("rank"), rowNumber),
                Collections.singletonList(AnalysisFixtures.ref("partition", "scan.dept")), Collections.singletonList(new Relations.OrderKey(
                AnalysisFixtures.ref("order", "scan.price"), Relations.Direction.DESCENDING, Relations.NullOrder.LAST)), null);
        Relations.Window window = new Relations.Window(new NodeId("window"), OutputSchema.known(slots), new QueryBlockId("main"), 1, null,
                scan.getId(), AnalysisFixtures.passthrough(scan.getOutputSchema()), Collections.singletonList(computation));
        Relations.Project project = project("project", window.getId(), "rank.out", AnalysisFixtures.ref("rank.ref", "rank"));
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, window, project), Collections.singletonList(write(project, "rank.out"))));
        assertTrue(facts.getWriteDependencies().stream().anyMatch(item -> item.getRole() == DependencyRole.CONDITION
                && "window.partition".equals(item.getEvidence().getRuleId())));
        assertTrue(facts.getWriteDependencies().stream().anyMatch(item -> item.getRole() == DependencyRole.CONDITION
                && "window.order".equals(item.getEvidence().getRuleId())));
    }

    @Test
    public void unionMapsBothBranchesBySlotPosition() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "left.block", "id");
        Relations.Scan right = AnalysisFixtures.scan("right", "b", "right.block", "key");
        Relations.SetOp union = new Relations.SetOp(new NodeId("union"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("union.out"))),
                new QueryBlockId("main"), 1, null, Arrays.asList(left.getId(), right.getId()), Relations.SetOperator.UNION, Relations.DuplicatePolicy.ALL,
                Collections.singletonList(new Relations.SetOutputMapping(new SlotId("union.out"), Arrays.asList(
                        Relations.BranchSlot.known(new SlotId("left.id")), Relations.BranchSlot.known(new SlotId("right.key"))))));
        LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, union), Collections.emptyList()));
        List<Dependency> output = relation(facts, "union").getOutputFacts().get(new SlotId("union.out")).getDependencies();
        assertTrue(hasColumn(output, "a.id", DependencyRole.VALUE));
        assertTrue(hasColumn(output, "b.key", DependencyRole.VALUE));
    }

    @Test
    public void partiallyUnknownSetKeepsIndependentKnownFactsAcrossOperatorsAndBranchOrder() {
        for (Relations.SetOperator operator : Relations.SetOperator.values()) {
            for (boolean unknownFirst : Arrays.asList(true, false)) {
                Relations.Scan scan = AnalysisFixtures.scan("scan", "source", "main", "id");
                Relations.Project unknown = project("unknown", scan.getId(), "unknown.out", new Expressions.UnknownReference(new NodeId("missing"),
                        TypeRef.unknown(), null, Collections.emptyList(), Collections.emptyList(), "UNKNOWN_QUALIFIED_OWNER"));
                Relations.Project known = project("known", scan.getId(), "known.out", AnalysisFixtures.ref("known.ref", "scan.id"));
                List<NodeId> branches = unknownFirst ? Arrays.asList(unknown.getId(), known.getId()) : Arrays.asList(known.getId(), unknown.getId());
                List<Relations.BranchSlot> branchSlots = unknownFirst
                        ? Arrays.asList(Relations.BranchSlot.known(new SlotId("unknown.out")), Relations.BranchSlot.known(new SlotId("known.out")))
                        : Arrays.asList(Relations.BranchSlot.known(new SlotId("known.out")), Relations.BranchSlot.known(new SlotId("unknown.out")));
                Relations.SetOp set = new Relations.SetOp(new NodeId("set"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("set.out"))),
                        new QueryBlockId("main"), 3, null, branches, operator, Relations.DuplicatePolicy.ALL,
                        Collections.singletonList(new Relations.SetOutputMapping(new SlotId("set.out"), branchSlots)));
                LineageFacts facts = analyze(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, unknown, known, set), Collections.emptyList()));
                List<Dependency> output = relation(facts, "set").getOutputFacts().get(new SlotId("set.out")).getDependencies();
                assertTrue(hasColumn(output, "source.id", DependencyRole.VALUE));
                assertTrue(output.stream().anyMatch(value -> value.getSource() instanceof UnknownEndpoint));
                assertEquals(Coverage.PARTIAL, facts.getQuality().getCoverage());
                assertEquals(Validity.UNDETERMINED, facts.getQuality().getValidity());
            }
        }
    }

    private LineageFacts analyze(BoundAnalysisUnit unit) {
        return new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS,
                BindingRequirement.OUTPUT_SCHEMA, BindingRequirement.SINK_MAPPING));
    }

    private Relations.Project project(String id, NodeId input, String output, com.panfan.hivelineage.v2.ir.expression.BoundExpression expression) {
        return new Relations.Project(new NodeId(id), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output(output))),
                new QueryBlockId("main"), 2, null, input, Collections.singletonList(new OutputAssignment(new SlotId(output), expression)));
    }

    private BoundWrite write(Relations.Project source, String slot) {
        return new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.notRequested(), VersionRef.notRequested(), BoundWrite.WriteMode.APPEND,
                Collections.singletonList(new WriteAssignment(new FieldIdentity("target.result", "result"), AnalysisFixtures.ref("write.ref", slot))),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE,
                Collections.emptyList(), source.getId());
    }

    private RelationFacts relation(LineageFacts facts, String id) {
        return facts.getRelations().stream().filter(item -> item.getRelationId().equals(new NodeId(id))).findFirst().get();
    }

    private boolean hasColumn(List<Dependency> facts, String field, DependencyRole role) {
        return facts.stream().anyMatch(item -> item.getRole() == role && item.getSource() instanceof ColumnEndpoint
                && ((ColumnEndpoint) item.getSource()).getFieldId().getValue().equals(field));
    }
}
