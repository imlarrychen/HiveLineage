package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.ir.IrTestBuilder;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class OutputSchemaContractTest {
    @Test
    public void duplicateNamesRetainDistinctOrderedSlotsAndDefensiveCopy() {
        OutputSlot first = IrTestBuilder.slot("left.id", "id");
        OutputSlot second = IrTestBuilder.slot("right.id", "id");
        List<OutputSlot> input = new ArrayList<OutputSlot>(Arrays.asList(first, second));
        OutputSchema schema = OutputSchema.known(input);
        input.clear();
        List<OutputSlot> slots = ((KnownOutputSegment) schema.getSegments().get(0)).getSlots();
        assertEquals(2, slots.size());
        assertSame(first, slots.get(0));
        assertSame(second, slots.get(1));
        assertEquals(first.getDisplayName(), second.getDisplayName());
        assertNotEquals(first.getId(), second.getId());
        try {
            slots.clear();
            fail("output must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(2, slots.size());
        }
    }

    @Test
    public void unknownWidthRetainsPrefixAndSuffixWithoutInventedStarSlot() {
        OutputSlot prefix = IrTestBuilder.slot("prefix", "id");
        OutputSlot suffix = IrTestBuilder.slot("suffix", "total");
        UnknownOutputSegment gap = new UnknownOutputSegment(new NodeId("gap"), "SCHEMA_MISSING");
        OutputSchema schema = new OutputSchema(SchemaState.UNKNOWN, Arrays.<OutputSegment>asList(
                new KnownOutputSegment(Collections.singletonList(prefix)), gap,
                new KnownOutputSegment(Collections.singletonList(suffix))));
        assertEquals(SchemaState.UNKNOWN, schema.getState());
        assertEquals(3, schema.getSegments().size());
        assertSame(gap, schema.getSegments().get(1));
        assertEquals(SchemaState.NOT_REQUESTED, OutputSchema.notRequested().getState());
        assertEquals(0, OutputSchema.notRequested().getSegments().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void knownSchemaCannotHideUnknownWidth() {
        new OutputSchema(SchemaState.KNOWN, Collections.<OutputSegment>singletonList(
                new UnknownOutputSegment(new NodeId("gap"), "SCHEMA_MISSING")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void notRequestedCannotContainOutputSlots() {
        new OutputSchema(SchemaState.NOT_REQUESTED, Collections.<OutputSegment>singletonList(
                new KnownOutputSegment(Collections.singletonList(IrTestBuilder.slot("id", "id")))));
    }

    @Test
    public void bindingCoverageFreezesNestedMapsAndDistinguishesUnknownFromSkipped() {
        NodeId region = new NodeId("projection");
        Map<BindingRequirement, BindingCoverage.State> inner = new LinkedHashMap<BindingRequirement, BindingCoverage.State>();
        inner.put(BindingRequirement.OBJECTS, BindingCoverage.State.COMPLETE);
        inner.put(BindingRequirement.OUTPUT_SCHEMA, BindingCoverage.State.NOT_REQUESTED);
        inner.put(BindingRequirement.SINK_MAPPING, BindingCoverage.State.UNKNOWN);
        Map<NodeId, Map<BindingRequirement, BindingCoverage.State>> outer = new LinkedHashMap<NodeId, Map<BindingRequirement, BindingCoverage.State>>();
        outer.put(region, inner);
        BindingCoverage coverage = new BindingCoverage(outer);
        inner.clear();
        outer.clear();
        assertEquals(BindingCoverage.State.COMPLETE, coverage.getRegions().get(region).get(BindingRequirement.OBJECTS));
        assertEquals(BindingCoverage.State.NOT_REQUESTED, coverage.getRegions().get(region).get(BindingRequirement.OUTPUT_SCHEMA));
        assertEquals(BindingCoverage.State.UNKNOWN, coverage.getRegions().get(region).get(BindingRequirement.SINK_MAPPING));
        try {
            coverage.getRegions().get(region).clear();
            fail("nested coverage must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(3, coverage.getRegions().get(region).size());
        }
    }
}
