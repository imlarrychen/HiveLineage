package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundScopeReference;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultIrVerifierTest {
    @Test
    public void duplicateNodesAndRelationCyclesNeverReceiveCertificate() {
        Relations.Scan first = AnalysisFixtures.scan("scan", "a", "main", "id");
        Relations.Scan duplicate = AnalysisFixtures.scan("scan", "b", "main", "id");
        VerificationResult duplicated = verify(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(first, duplicate), Collections.emptyList()), BindingRequirement.OBJECTS);
        assertRejected(duplicated, "IR_DUPLICATE_NODE");
        Relations.Filter cycle = new Relations.Filter(new NodeId("cycle"), first.getOutputSchema(), new QueryBlockId("main"), 1, null,
                new NodeId("cycle"), AnalysisFixtures.literal("true", "true"), AnalysisFixtures.passthrough(first.getOutputSchema()));
        assertRejected(verify(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(first, cycle), Collections.emptyList()), BindingRequirement.OBJECTS), "IR_RELATION_CYCLE");
    }

    @Test
    public void globallyDeclaredSlotCannotBeReferencedThroughAnUnrelatedInput() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "main", "id");
        Relations.Scan unrelated = AnalysisFixtures.scan("unrelated", "b", "other", "id");
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 1, null, left.getId(), Collections.singletonList(new OutputAssignment(new SlotId("out"),
                AnalysisFixtures.ref("foreign.ref", "unrelated.id"))));
        assertRejected(verify(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, unrelated, project), Collections.emptyList()), BindingRequirement.OBJECTS), "IR_SLOT_UNREACHABLE");
    }

    @Test
    public void setWidthAndPositionAreVerifiedIndependentlyFromDisplayNames() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "left", "id", "value");
        Relations.Scan right = AnalysisFixtures.scan("right", "b", "right", "id");
        Relations.SetOp set = new Relations.SetOp(new NodeId("set"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("set.out"))),
                new QueryBlockId("main"), 1, null, Arrays.asList(left.getId(), right.getId()), Relations.SetOperator.UNION, Relations.DuplicatePolicy.ALL,
                Collections.singletonList(new Relations.SetOutputMapping(new SlotId("set.out"), Arrays.asList(
                        Relations.BranchSlot.known(new SlotId("left.value")), Relations.BranchSlot.known(new SlotId("right.id"))))));
        VerificationResult result = verify(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, set), Collections.emptyList()), BindingRequirement.OBJECTS);
        assertRejected(result, "IR_SET_WIDTH_MISMATCH");
        assertTrue(result.getDiagnostics().getDiagnostics().stream().anyMatch(item -> "IR_SET_POSITION_INVALID".equals(item.getCode())));
    }

    @Test
    public void correlationRequiresDeclaredAncestorAndItsOwnSlot() {
        Relations.Scan outer = AnalysisFixtures.scan("outer", "a", "outer.block", "id");
        Relations.Scan inner = AnalysisFixtures.scan("inner", "b", "inner.block", "id");
        Expressions.CorrelatedSlotRef ref = new Expressions.CorrelatedSlotRef(new NodeId("correlated"), TypeRef.unknown(), null,
                new ScopeId("outer.scope"), new SlotId("outer.id"), Collections.emptyList());
        Relations.Filter filter = new Relations.Filter(new NodeId("filter"), inner.getOutputSchema(), new QueryBlockId("inner.block"), 1, null,
                inner.getId(), ref, AnalysisFixtures.passthrough(inner.getOutputSchema()));
        Map<ScopeId, BoundScopeReference> scopes = new LinkedHashMap<ScopeId, BoundScopeReference>();
        scopes.put(new ScopeId("outer.scope"), new BoundScopeReference(new ScopeId("outer.scope"), new QueryBlockId("outer.block"), Optional.empty()));
        scopes.put(new ScopeId("inner.scope"), new BoundScopeReference(new ScopeId("inner.scope"), new QueryBlockId("inner.block"), Optional.of(new ScopeId("outer.scope"))));
        BoundAnalysisUnit valid = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(outer, inner, filter), Collections.emptyList(), scopes);
        assertEquals(VerificationOutcome.ACCEPTED, verify(valid, BindingRequirement.OBJECTS).getOutcome());
        BoundAnalysisUnit invalid = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(outer, inner, filter), Collections.emptyList());
        assertRejected(verify(invalid, BindingRequirement.OBJECTS), "IR_CORRELATION_INVALID");
    }

    @Test
    public void notRequestedSchemaIsAcceptedForObjectsAndRequestsBindingForColumns() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "a", "main");
        Relations.Scan lightweight = new Relations.Scan(scan.getId(), OutputSchema.notRequested(), scan.getQueryBlockId(), 0, null,
                scan.getObjectId(), scan.getVersion(), scan.getInstanceId());
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(lightweight), Collections.emptyList());
        assertEquals(VerificationOutcome.ACCEPTED, verify(unit, BindingRequirement.OBJECTS).getOutcome());
        VerificationResult missing = verify(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        assertEquals(VerificationOutcome.NEEDS_BINDING, missing.getOutcome());
        assertTrue(!missing.getVerifiedUnit().isPresent());
    }

    @Test
    public void explicitUnknownRemainsAnalyzableWithoutBeingConvertedToEmptyDependency() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "a", "main", "id");
        Expressions.UnknownExpression unknown = new Expressions.UnknownExpression(new NodeId("unknown"), TypeRef.unknown(), null,
                Collections.singletonList(AnalysisFixtures.ref("known", "scan.id")), "UNKNOWN_BEHAVIOR");
        Relations.Project project = new Relations.Project(new NodeId("project"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 1, null, scan.getId(), Collections.singletonList(new OutputAssignment(new SlotId("out"), unknown)));
        assertEquals(VerificationOutcome.ACCEPTED, verify(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, project), Collections.emptyList()),
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA).getOutcome());
    }

    @Test
    public void requestedDatasetVersionsCannotReceiveCertificateWhenReadsAreNotRequested() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "a", "main", "id");
        assertEquals(VerificationOutcome.NEEDS_BINDING, verify(AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(scan),
                Collections.emptyList()), BindingRequirement.OBJECTS, BindingRequirement.DATASET_VERSIONS).getOutcome());
        Relations.Scan bound = new Relations.Scan(scan.getId(), scan.getOutputSchema(), scan.getQueryBlockId(), 0, null,
                scan.getObjectId(), VersionRef.known(new DatasetVersionId("entry")), scan.getInstanceId());
        assertEquals(VerificationOutcome.ACCEPTED, verify(AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(bound),
                Collections.emptyList()), BindingRequirement.OBJECTS, BindingRequirement.DATASET_VERSIONS).getOutcome());
    }

    private VerificationResult verify(BoundAnalysisUnit unit, BindingRequirement... requirements) {
        EnumSet<BindingRequirement> requested = EnumSet.noneOf(BindingRequirement.class);
        requested.addAll(Arrays.asList(requirements));
        return new DefaultIrVerifier().verify(unit, new BindingDemand(requested, Collections.emptySet()));
    }

    private void assertRejected(VerificationResult result, String code) {
        assertEquals(VerificationOutcome.REJECTED, result.getOutcome());
        assertTrue(!result.getVerifiedUnit().isPresent());
        assertTrue(result.getDiagnostics().getDiagnostics().stream().anyMatch(item -> code.equals(item.getCode())));
    }
}
