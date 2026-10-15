package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VerificationContextContractTest {
    @Test
    public void declarationViewsAndVisibilityAreReadOnlyAndKeepOccurrenceIdentity() {
        Relations.Scan left = AnalysisFixtures.scan("left", "same.object", "main", "id");
        Relations.Scan right = AnalysisFixtures.scan("right", "same.object", "main", "id");
        Relations.Filter filter = new Relations.Filter(new NodeId("filter"), left.getOutputSchema(), new QueryBlockId("main"), 1,
                null, left.getId(), AnalysisFixtures.literal("predicate", "true"), AnalysisFixtures.passthrough(left.getOutputSchema()));
        VerificationContext context = new VerificationContext(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, filter), Collections.emptyList()));
        VerificationContext.Visibility visibility = context.inputs(filter);
        assertTrue(visibility.owners().contains(left.getInstanceId()));
        assertFalse(visibility.owners().contains(right.getInstanceId()));
        assertEquals(Collections.singleton(new SlotId("left.id")), visibility.slots());
        assertSame(filter, context.relations().get(filter.getId()));
        assertReadOnly(() -> context.relations().clear());
        assertReadOnly(() -> context.outputs(left.getId()).clear());
        assertReadOnly(() -> context.slots(left.getId()).clear());
        assertReadOnly(() -> context.reachable(filter.getId()).clear());
        assertReadOnly(() -> visibility.slots().clear());
        assertReadOnly(() -> visibility.owners().clear());
    }

    @Test
    public void referenceRuleRejectsUnrelatedSlotsWithoutRepairingOrRebinding() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "main", "id");
        Relations.Scan unrelated = AnalysisFixtures.scan("unrelated", "b", "main", "id");
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, unrelated), Collections.emptyList());
        VerificationContext context = new VerificationContext(unit);
        VerificationDiagnostics diagnostics = new VerificationDiagnostics();
        new ReferenceReachabilityVerification(context, diagnostics).verify(AnalysisFixtures.ref("foreign", "unrelated.id"),
                left.getQueryBlockId(), new VerificationContext.Visibility(context.outputs(left.getId()), Collections.singleton(left.getInstanceId())));
        assertEquals(1, diagnostics.result().getDiagnostics().size());
        assertEquals("IR_SLOT_UNREACHABLE", diagnostics.result().getDiagnostics().get(0).getCode());
        assertEquals(AnalysisStage.VERIFICATION, diagnostics.result().getDiagnostics().get(0).getStage());
        assertSame(unit, context.unit());
        assertEquals(Collections.singleton(new SlotId("left.id")), context.outputs(left.getId()));
    }

    @Test
    public void setConstraintCanValidateWidthAndPositionWithoutRunningOtherStages() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "left", "id", "value");
        Relations.Scan right = AnalysisFixtures.scan("right", "b", "right", "id");
        Relations.SetOp set = new Relations.SetOp(new NodeId("set"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 1, null, Arrays.asList(left.getId(), right.getId()), Relations.SetOperator.UNION,
                Relations.DuplicatePolicy.ALL, Collections.singletonList(new Relations.SetOutputMapping(new SlotId("out"),
                Arrays.asList(Relations.BranchSlot.known(new SlotId("left.value")), Relations.BranchSlot.known(new SlotId("right.id"))))));
        VerificationContext context = new VerificationContext(AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, set), Collections.emptyList()));
        VerificationDiagnostics diagnostics = new VerificationDiagnostics();
        new SetOperatorVerification(context, diagnostics).verify(set, new OperatorOutputState(set, context, diagnostics));
        assertEquals(2, diagnostics.result().getDiagnostics().size());
        assertEquals("IR_SET_WIDTH_MISMATCH", diagnostics.result().getDiagnostics().get(0).getCode());
        assertEquals("IR_SET_POSITION_INVALID", diagnostics.result().getDiagnostics().get(1).getCode());
    }

    @Test
    public void versionRuleOnlyChecksDemandAndNeverInventsReadVersions() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "a", "main", "id");
        VerificationContext context = new VerificationContext(AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(scan), Collections.emptyList()));
        VerificationDiagnostics unrequested = new VerificationDiagnostics();
        new VersionBindingVerification(context, new BindingDemand(EnumSet.of(BindingRequirement.OBJECTS), Collections.emptySet()), unrequested).relation(scan);
        assertTrue(unrequested.result().getDiagnostics().isEmpty());
        VerificationDiagnostics requested = new VerificationDiagnostics();
        new VersionBindingVerification(context, new BindingDemand(EnumSet.of(BindingRequirement.DATASET_VERSIONS), Collections.emptySet()), requested).relation(scan);
        assertEquals("BINDING_NEEDED", requested.result().getDiagnostics().get(0).getCode());
        assertSame(scan, context.relations().get(scan.getId()));
    }

    @Test
    public void duplicateNodeDoesNotMakeSetRuleValidateAnUnrelatedObjectsSchema() {
        Relations.Scan left = AnalysisFixtures.scan("left", "a", "left", "id");
        Relations.Scan right = AnalysisFixtures.scan("right", "b", "right", "id");
        Relations.SetOp set = new Relations.SetOp(new NodeId("same"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("out"))),
                new QueryBlockId("main"), 1, null, Arrays.asList(left.getId(), right.getId()), Relations.SetOperator.UNION,
                Relations.DuplicatePolicy.ALL, Collections.singletonList(new Relations.SetOutputMapping(new SlotId("out"),
                Arrays.asList(Relations.BranchSlot.known(new SlotId("left.id")), Relations.BranchSlot.known(new SlotId("right.id"))))));
        Relations.Values duplicate = new Relations.Values(set.getId(), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("other"))),
                new QueryBlockId("main"), 2, null, Collections.singletonList(Collections.singletonList(AnalysisFixtures.literal("literal", "1"))));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, set, duplicate), Collections.emptyList());
        java.util.List<com.panfan.hivelineage.v2.common.Diagnostic> diagnostics = new DefaultStructuralVerifier().verifyStructure(unit).getDiagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("IR_DUPLICATE_NODE", diagnostics.get(0).getCode());
        VerificationContext context = new VerificationContext(unit);
        assertEquals(new QueryBlockId("main"), context.slotBlock(new SlotId("other")));
        assertEquals(null, context.slotBlock(new SlotId("out")));
    }

    private void assertReadOnly(Runnable mutation) {
        try {
            mutation.run();
            fail("Verification declarations must not be mutable through a rule");
        } catch (UnsupportedOperationException expected) {
            return;
        }
    }
}
