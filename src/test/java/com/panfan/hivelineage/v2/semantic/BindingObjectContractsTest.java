package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** These invariants can be verified without a parser, metadata provider or analysis pipeline. */
public class BindingObjectContractsTest {
    @Test
    public void physicalSchemaCanBeCompletedOnceAndUncertaintyCannotBePromoted() {
        SourceBinding source = physical("a", Collections.singletonList("a"), null);
        assertTrue(source.beginSchemaLoad());
        assertFalse(source.beginSchemaLoad());
        OutputSchema known = OutputSchema.known(Collections.singletonList(slot("id")));
        source.completeSchemaLoad(known);
        assertSame(known, source.schema());
        assertFalse(source.beginSchemaLoad());
        assertThrows(IllegalStateException.class, () -> source.completeSchemaLoad(known));
        source.markUncertain();
        assertTrue(source.isUncertain());
        assertEquals(SchemaState.UNKNOWN, source.schema().getState());
        assertFalse(source.beginSchemaLoad());
        assertThrows(IllegalStateException.class, () -> source.completeSchemaLoad(known));
    }

    @Test
    public void sourceFactoriesCopyAliasesAndSeparateDerivedAndPhysicalState() {
        List<String> aliases = new ArrayList<>(Collections.singletonList("a"));
        SourceBinding physical = physical("a", aliases, null);
        aliases.add("injected");
        assertFalse(physical.aliases.contains("injected"));
        assertThrows(UnsupportedOperationException.class, () -> physical.aliases.clear());
        SourceBinding derived = SourceBinding.derived(new NodeId("derived"), new RelationInstanceId("derived"), null,
                name("d"), null, OutputSchema.known(Collections.singletonList(slot("id"))));
        assertFalse(derived.beginSchemaLoad());
        assertThrows(IllegalStateException.class, derived::markUncertain);
        SourceBinding uncertain = SourceBinding.physical(new NodeId("uncertain"), new RelationInstanceId("uncertain"),
                new ObjectId("object"), name("a"), aliases, null, OutputSchema.known(Collections.singletonList(slot("id"))), true, null);
        assertEquals(SchemaState.UNKNOWN, uncertain.schema().getState());
    }

    @Test
    public void symbolsKeepExplicitAliasPriorityAndDoNotExposeMutableSources() {
        ScopeSymbols symbols = new ScopeSymbols();
        SourceBinding implicit = physical("first", Collections.singletonList("a"), null);
        SourceBinding explicit = physical("second", Arrays.asList("b", "a"), "a");
        symbols.register(implicit);
        symbols.register(explicit);
        assertEquals(Collections.singletonList(explicit), symbols.qualified("a"));
        assertEquals(Collections.singletonList(explicit), symbols.qualified("b"));
        assertThrows(UnsupportedOperationException.class, () -> symbols.sources().clear());
        assertThrows(UnsupportedOperationException.class, () -> symbols.qualified("a").clear());
    }

    @Test
    public void ownerIndexesFollowSchemaDiscoveryAndEmptyVisibility() {
        ScopeSymbols symbols = new ScopeSymbols();
        SourceBinding source = physical("a", Collections.singletonList("a"), null);
        symbols.register(source);
        assertTrue(symbols.slotOwners().isEmpty());
        OutputSlot id = slot("id");
        assertTrue(source.beginSchemaLoad());
        source.completeSchemaLoad(OutputSchema.known(Collections.singletonList(id)));
        assertEquals(source.instanceId, symbols.slotOwners().get(id.getId()));
        symbols.replaceSources(Collections.<SourceBinding>emptyList());
        assertTrue(symbols.slotOwners().isEmpty());
        symbols.register(source);
        assertEquals(source.instanceId, symbols.slotOwners().get(id.getId()));
        source.markUncertain();
        assertTrue(symbols.slotOwners().isEmpty());
    }

    @Test
    public void projectionBuilderPairsDefinitionsAndRejectsWholeInvalidGroups() {
        ProjectionLayout.Builder builder = new ProjectionLayout.Builder();
        OutputSlot first = slot("first");
        BoundExpression expression = literal();
        builder.addVisible(first, expression);
        assertThrows(IllegalArgumentException.class, () -> builder.addVisibleGroup(Arrays.asList(
                new ProjectionLayout.Column(slot("second"), expression), new ProjectionLayout.Column(first, expression))));
        ProjectionLayout before = builder.build();
        assertEquals(1, before.assignments().size());
        builder.addHidden(slot("hidden"), expression);
        ProjectionLayout after = builder.build();
        assertEquals(1, BindingWork.slots(before.operatorSchema()).size());
        assertEquals(1, BindingWork.slots(after.visibleSchema()).size());
        assertEquals(2, BindingWork.slots(after.operatorSchema()).size());
        assertEquals(2, after.assignments().size());
        assertThrows(UnsupportedOperationException.class, () -> after.assignments().clear());
    }

    @Test
    public void visibilityProjectionPreservesUnknownWidthAndHidesComputations() {
        ProjectionLayout.Builder builder = new ProjectionLayout.Builder();
        builder.addVisible(slot("visible"), literal());
        builder.addUnknown(new UnknownOutputSegment(new NodeId("width"), "SCHEMA_UNKNOWN"));
        builder.addHidden(slot("hidden"), literal());
        ProjectionLayout layout = builder.build();
        ProjectionLayout visible = layout.visibleProjection(null);
        assertEquals(SchemaState.UNKNOWN, visible.visibleSchema().getState());
        assertEquals(2, visible.visibleSchema().getSegments().size());
        assertEquals(1, visible.assignments().size());
        assertEquals("visible/visible-output", visible.assignments().get(0).getOutput().getValue());
        assertFalse(visible.hasHidden());
        ProjectionLayout copy = layout.toBuilder().build();
        assertEquals(2, copy.assignments().size());
        assertTrue(copy.hasHidden());
    }

    @Test
    public void windowRegistrationRejectsMismatchesAndDuplicatesAtomically() {
        WindowLayout windows = new WindowLayout();
        OutputSlot first = slot("first");
        Relations.WindowComputation computation = computation(first.getId());
        assertThrows(IllegalArgumentException.class, () -> windows.add(slot("other"), computation));
        assertTrue(windows.slots().isEmpty());
        windows.add(first, computation);
        assertThrows(IllegalArgumentException.class, () -> windows.add(first, computation));
        assertEquals(1, windows.slots().size());
        assertEquals(1, windows.computations().size());
        assertEquals(first.getId(), windows.computations().get(0).getAssignment().getOutput());
    }

    @Test
    public void joinVisibilityExposesBothInputsToConditionsAndOnlyLeftToSemiOutputs() {
        SourceBinding left = physical("left", Collections.singletonList("a"), null);
        SourceBinding right = physical("right", Collections.singletonList("b"), null);
        Relations.Values relation = new Relations.Values(new NodeId("values"), OutputSchema.known(Collections.<OutputSlot>emptyList()),
                new QueryBlockId("block"), 0, null, Collections.<List<BoundExpression>>emptyList());
        BoundFrom.Visibility visibility = BoundFrom.Visibility.join(BoundFrom.source(relation, left), BoundFrom.source(relation, right), true);
        ScopeSymbols symbols = new ScopeSymbols();
        visibility.exposeConditions(symbols, Collections.<SourceBinding>emptyList());
        assertEquals(Arrays.asList(left, right), symbols.sources());
        BoundFrom result = new BoundFrom(relation, visibility);
        assertEquals(Arrays.asList(left, right), result.conditionSources);
        assertEquals(Collections.singletonList(left), result.outputSources);
        assertThrows(UnsupportedOperationException.class, () -> result.outputSources.clear());
        assertThrows(IllegalArgumentException.class, () -> new BoundFrom.Visibility(
                Collections.singletonList(left), Collections.singletonList(right)));
    }

    @Test
    public void definitionResultsMakeUnavailableAndDeferredRelationsInaccessible() {
        assertEquals(DefinitionExpander.Result.Status.DEFERRED, DefinitionExpander.Result.deferred().status);
        assertThrows(IllegalStateException.class, () -> DefinitionExpander.Result.deferred().relation());
        assertThrows(IllegalStateException.class, () -> DefinitionExpander.Result.unavailable().relation());
        assertThrows(NullPointerException.class, () -> DefinitionExpander.Result.expanded(null));
    }

    private SourceBinding physical(String id, List<String> aliases, String explicitAlias) {
        return SourceBinding.physical(new NodeId(id), new RelationInstanceId(id), new ObjectId("object/" + id),
                name(id), aliases, null, OutputSchema.notRequested(), false, explicitAlias);
    }

    private QualifiedName name(String value) {
        return new QualifiedName(Collections.singletonList(value));
    }

    private OutputSlot slot(String value) {
        return new OutputSlot(new SlotId(value), value, TypeRef.unknown(), null);
    }

    private BoundExpression literal() {
        return new Expressions.Literal(new NodeId("literal"), TypeRef.unknown(), null,
                new Expressions.LiteralValue(Expressions.LiteralValue.Kind.INTEGER, "1"));
    }

    private Relations.WindowComputation computation(SlotId slot) {
        return new Relations.WindowComputation(new OutputAssignment(slot, literal()), Collections.<BoundExpression>emptyList(),
                Collections.<Relations.OrderKey>emptyList(), null);
    }
}
