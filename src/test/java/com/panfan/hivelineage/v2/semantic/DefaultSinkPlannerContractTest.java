package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.UnknownOutputSegment;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

/** Direct policy contracts keep mappings independent of parser and analysis orchestration. */
public class DefaultSinkPlannerContractTest {
    @Test
    public void policyRegistrationUsesThePublicImmutableMappingContract() {
        Map<StarMappingPolicy, SinkMappingStrategy> registrations = new EnumMap<>(StarMappingPolicy.class);
        registrations.put(StarMappingPolicy.POSITIONAL_EXACT, (query, request, layout) -> {
            assertEquals("a", layout.getFields().get(0).getDisplayName());
            assertThrows(UnsupportedOperationException.class, () -> layout.getFields().clear());
            return MappingPlan.complete(Collections.singletonList(new WriteAssignment(layout.getFields().get(0),
                    unknown("custom"))), MappingCoverage.COMPLETE);
        });
        DefaultSinkPlanner planner = new DefaultSinkPlanner(registrations);
        registrations.clear();
        BoundRelation query = new Relations.Values(new NodeId("query"), schema("x", "y"), new QueryBlockId("block"),
                0, null, Collections.<List<BoundExpression>>emptyList());
        SinkBindingRequest request = new SinkBindingRequest(new NodeId("write"), new ObjectId("target"), schema("a"),
                Collections.<FieldIdentity>emptyList(), noPartitions(), BoundWrite.WriteMode.APPEND,
                VersionRef.notRequested(), VersionRef.notRequested(), StarMappingPolicy.POSITIONAL_EXACT);
        assertEquals("custom", planner.plan(query, request).getWrite().get().getAssignments().get(0).getExpression().getId().getValue());
    }

    @Test
    public void positionalMappingRejectsWidthMismatchEvenWithStar() {
        SinkPlanningResult result = plan(schema("b"), schema("a", "b"),
                Collections.<FieldIdentity>emptyList(), StarMappingPolicy.POSITIONAL_EXACT, true, noPartitions());
        assertFalse(result.getWrite().isPresent());
        assertEquals("COLUMN_COUNT_MISMATCH", result.getDiagnostics().getDiagnostics().get(0).getCode());
    }

    @Test
    public void namedPoliciesUseUniqueNamesAndPreserveUnknownAssignments() {
        for (StarMappingPolicy policy : Arrays.asList(StarMappingPolicy.EXPLICIT_LEGACY_TARGET_MAPPING,
                StarMappingPolicy.CURRENT_API_TARGET_MAPPING, StarMappingPolicy.SPEC_TARGET_SCHEMA_MAPPING)) {
            SinkPlanningResult result = plan(schema("b", "a", "a"), schema("a", "b"),
                    fields("a", "b"), policy, true, noPartitions());
            BoundWrite write = result.getWrite().get();
            assertEquals(MappingCoverage.PARTIAL, write.getMappingCoverage());
            assertTrue(write.getAssignments().get(0).getExpression() instanceof Expressions.UnknownExpression);
            assertEquals(new SlotId("source/b/0"), ((Expressions.SlotRef) write.getAssignments().get(1).getExpression()).getSlotId());
            assertEquals("write/unknown-0", write.getAssignments().get(0).getExpression().getId().getValue());
            assertEquals(policy.name(), result.getDiagnostics().getDiagnostics().get(0).getCode());
        }
    }

    @Test
    public void currentApiInfersUnknownTargetsAndAllowsKnownPrefix() {
        SinkPlanningResult inferred = plan(schema("ID", "Name"), unknownSchema(),
                Collections.<FieldIdentity>emptyList(), StarMappingPolicy.CURRENT_API_TARGET_MAPPING, false, noPartitions());
        assertEquals("target/field/id", inferred.getWrite().get().getAssignments().get(0).getTargetField().getValue());
        assertEquals(MappingCoverage.PARTIAL, inferred.getWrite().get().getMappingCoverage());
        SinkPlanningResult prefix = plan(schema("x"), schema("a", "b"),
                Collections.<FieldIdentity>emptyList(), StarMappingPolicy.CURRENT_API_TARGET_MAPPING, false, noPartitions());
        assertEquals(1, prefix.getWrite().get().getAssignments().size());
        assertEquals("a", prefix.getWrite().get().getAssignments().get(0).getTargetField().getDisplayName());
        assertEquals(MappingCoverage.PARTIAL, prefix.getWrite().get().getMappingCoverage());
    }

    @Test
    public void partitionsAlwaysMapDataThenDynamicThenStatic() {
        BoundExpression literal = unknown("partition");
        PartitionScope partitions = new PartitionScope(PartitionScope.Kind.MIXED, Arrays.asList(
                new PartitionScope.Assignment(field("dt"), PartitionScope.AssignmentKind.STATIC, literal),
                new PartitionScope.Assignment(field("region"), PartitionScope.AssignmentKind.DYNAMIC, unknown("dynamic"))));
        BoundWrite write = plan(schema("sourceA", "sourceRegion"), schema("a", "dt", "region"),
                Collections.<FieldIdentity>emptyList(), StarMappingPolicy.POSITIONAL_EXACT, false, partitions).getWrite().get();
        assertEquals(Arrays.asList("a", "region", "dt"), Arrays.asList(write.getAssignments().get(0).getTargetField().getDisplayName(),
                write.getAssignments().get(1).getTargetField().getDisplayName(), write.getAssignments().get(2).getTargetField().getDisplayName()));
        assertSame(literal, write.getAssignments().get(2).getExpression());
        assertEquals(MappingCoverage.COMPLETE, write.getMappingCoverage());
    }

    @Test
    public void sourceUnknownAndUnrequestedKeepDifferentCoverageContracts() {
        SinkPlanningResult unknown = plan(unknownSchema(), schema("a"), Collections.<FieldIdentity>emptyList(),
                StarMappingPolicy.CURRENT_API_TARGET_MAPPING, false, noPartitions());
        assertEquals(MappingCoverage.UNKNOWN, unknown.getWrite().get().getMappingCoverage());
        assertEquals("SOURCE_WIDTH_UNKNOWN", unknown.getWrite().get().getUnknownMappings().get(0).getDiagnosticCode());
        SinkPlanningResult unrequested = plan(OutputSchema.notRequested(), OutputSchema.notRequested(),
                Collections.<FieldIdentity>emptyList(), StarMappingPolicy.POSITIONAL_EXACT, false, noPartitions());
        assertEquals(MappingCoverage.NOT_REQUESTED, unrequested.getWrite().get().getMappingCoverage());
        assertTrue(unrequested.getWrite().get().getAssignments().isEmpty());
    }

    private SinkPlanningResult plan(OutputSchema source, OutputSchema target, List<FieldIdentity> explicit,
                                    StarMappingPolicy policy, boolean star, PartitionScope partitions) {
        BoundRelation query = new Relations.Values(new NodeId("query"), source, new QueryBlockId("query-block"), 0,
                null, Collections.<List<BoundExpression>>emptyList());
        SinkBindingRequest request = new SinkBindingRequest(new NodeId("write"), new ObjectId("target"), target, explicit,
                partitions, BoundWrite.WriteMode.APPEND, VersionRef.notRequested(), VersionRef.notRequested(), policy, star);
        return new DefaultSinkPlanner().plan(query, request);
    }

    private static OutputSchema schema(String... names) {
        List<OutputSlot> slots = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            slots.add(new OutputSlot(new SlotId("source/" + names[index] + "/" + index), names[index], TypeRef.unknown(), field(names[index])));
        }
        return OutputSchema.known(slots);
    }

    private static OutputSchema unknownSchema() {
        return new OutputSchema(SchemaState.UNKNOWN, Collections.singletonList(new UnknownOutputSegment(new NodeId("unknown"), "UNKNOWN")));
    }

    private static List<FieldIdentity> fields(String... names) {
        List<FieldIdentity> result = new ArrayList<>();
        for (String name : names) {
            result.add(field(name));
        }
        return result;
    }

    private static FieldIdentity field(String name) {
        return new FieldIdentity("target/field/" + name, name);
    }

    private static BoundExpression unknown(String id) {
        return new Expressions.UnknownExpression(new NodeId(id), TypeRef.unknown(), null, Collections.<BoundExpression>emptyList(), "TEST");
    }

    private static PartitionScope noPartitions() {
        return new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.<PartitionScope.Assignment>emptyList());
    }
}
