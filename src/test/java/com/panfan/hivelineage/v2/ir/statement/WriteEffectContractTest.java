package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class WriteEffectContractTest {
    @Test
    public void writeDoesNotRequireDatasetVersionsAndPreservesPartialMapping() {
        BoundExpression value = new Expressions.Literal(new NodeId("literal"), TypeRef.unknown(), null,
                new Expressions.LiteralValue(Expressions.LiteralValue.Kind.INTEGER, "1"));
        WriteAssignment assignment = new WriteAssignment(new FieldIdentity("target.id", "id"), value);
        List<WriteAssignment> assignments = new ArrayList<WriteAssignment>(Collections.singletonList(assignment));
        UnknownMapping unknown = new UnknownMapping(new NodeId("output.gap"), UnknownMapping.Reason.SOURCE_WIDTH_UNKNOWN, "UNKNOWN_WIDTH");
        BoundWrite write = new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.notRequested(), VersionRef.notRequested(),
                BoundWrite.WriteMode.APPEND, assignments, new PartitionScope(PartitionScope.Kind.UNPARTITIONED,
                Collections.<PartitionScope.Assignment>emptyList()), null, MappingCoverage.PARTIAL, Collections.singletonList(unknown));
        assignments.clear();
        assertEquals(VersionRef.State.NOT_REQUESTED, write.getBeforeVersion().getState());
        assertEquals(VersionRef.State.NOT_REQUESTED, write.getAfterVersion().getState());
        assertFalse(write.getBeforeVersion().getId().isPresent());
        assertSame(assignment, write.getAssignments().get(0));
        assertSame(unknown, write.getUnknownMappings().get(0));
        assertEquals(MappingCoverage.PARTIAL, write.getMappingCoverage());
        assertFalse(write.getCondition().isPresent());
    }

    @Test
    public void staticAndDynamicPartitionAssignmentsAreExplicitBoundValues() {
        BoundExpression value = new Expressions.Literal(new NodeId("day"), TypeRef.unknown(), null,
                new Expressions.LiteralValue(Expressions.LiteralValue.Kind.STRING, "2026-10-12"));
        PartitionScope partitions = new PartitionScope(PartitionScope.Kind.MIXED, Arrays.asList(
                new PartitionScope.Assignment(new FieldIdentity("target.day", "day"), PartitionScope.AssignmentKind.STATIC, value),
                new PartitionScope.Assignment(new FieldIdentity("target.region", "region"), PartitionScope.AssignmentKind.DYNAMIC,
                        new Expressions.SlotRef(new NodeId("region.ref"), TypeRef.unknown(), null,
                                new SlotId("input.region"), Collections.<String>emptyList()))));
        assertEquals(PartitionScope.Kind.MIXED, partitions.getKind());
        assertEquals(PartitionScope.AssignmentKind.STATIC, partitions.getAssignments().get(0).getKind());
        assertEquals(PartitionScope.AssignmentKind.DYNAMIC, partitions.getAssignments().get(1).getKind());
    }

    @Test
    public void effectsAreTypedProposalsAndLogicalDefinitionsHaveNoFakePhysicalObject() {
        Effects.Precondition precondition = new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
        Effects.SetDatabase database = new Effects.SetDatabase(new NodeId("use"), Effects.Certainty.CERTAIN, precondition,
                new QualifiedName(Collections.singletonList("ods")));
        Effects.RegisterDefinition definition = new Effects.RegisterDefinition(new NodeId("cte.register"), Effects.Certainty.CERTAIN, precondition,
                new DefinitionId("cte"), new QualifiedName(Collections.singletonList("cte_name")), OutputSchema.notRequested(),
                new NodeId("cte.root"), null);
        List<BoundEffect> proposals = Arrays.<BoundEffect>asList(database, definition);
        assertEquals(Effects.EffectKind.SET_DATABASE, proposals.get(0).getKind());
        assertEquals(Effects.EffectKind.REGISTER_DEFINITION, proposals.get(1).getKind());
        assertFalse(definition.getPhysicalObject().isPresent());
        assertEquals(new DefinitionId("cte"), definition.getDefinitionId());
        assertSame(precondition, definition.getPrecondition());
    }
}
