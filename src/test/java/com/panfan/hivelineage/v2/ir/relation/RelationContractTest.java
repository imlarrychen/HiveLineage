package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.ir.IrTestBuilder;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class RelationContractTest {
    @Test
    public void manuallyBuiltJoinGraphRetainsCommaPredicatesAndStableQueryBlockWithoutSql() {
        Relations.Scan left = IrTestBuilder.scan("left", "left.instance", "orders", OutputSchema.notRequested());
        Relations.Scan right = IrTestBuilder.scan("right", "right.instance", "orders", OutputSchema.notRequested());
        BoundExpression predicate = new Expressions.UnknownExpression(new NodeId("predicate"), TypeRef.unknown(), null,
                Collections.<BoundExpression>emptyList(), "CONDITION_PARTIAL");
        Relations.JoinPredicate tagged = new Relations.JoinPredicate(predicate, Relations.PredicateOrigin.WHERE,
                Arrays.asList(left.getInstanceId(), right.getInstanceId()), 10);
        Relations.Join join = new Relations.Join(new NodeId("join"), OutputSchema.notRequested(), new QueryBlockId("main"), 11, null,
                left.getId(), right.getId(), Relations.JoinType.INNER, Relations.JoinForm.COMMA, predicate,
                Collections.<SlotMapping>emptyList(), Collections.singletonList(tagged));
        List<BoundRelation> nodes = new ArrayList<BoundRelation>(Arrays.<BoundRelation>asList(left, right, join));
        BoundAnalysisUnit unit = IrTestBuilder.unit(nodes);
        nodes.clear();
        assertEquals(3, unit.getRelations().size());
        assertEquals(Arrays.asList(left.getId(), right.getId()), join.getInputIds());
        assertEquals(Relations.JoinForm.COMMA, join.getJoinForm());
        assertEquals(Relations.PredicateOrigin.WHERE, join.getPredicates().get(0).getOrigin());
        assertEquals(new QueryBlockId("main"), join.getQueryBlockId());
        assertEquals(11, join.getSourceOrder());
        assertEquals(2, tagged.getOwners().size());
        assertFalse(join.getSourceRef().isPresent());
    }

    @Test
    public void setOperationsPreservePerBranchUnknownInsteadOfInventingPosition() {
        SlotId output = new SlotId("union.output");
        Relations.SetOutputMapping mapping = new Relations.SetOutputMapping(output, Arrays.asList(
                Relations.BranchSlot.known(new SlotId("left.id")), Relations.BranchSlot.unknown("BRANCH_SCHEMA_UNKNOWN")));
        Relations.SetOp union = new Relations.SetOp(new NodeId("union"), OutputSchema.known(Collections.singletonList(
                IrTestBuilder.slot("union.output", "id"))), new QueryBlockId("main"), 0, null,
                Arrays.asList(new NodeId("left"), new NodeId("right")), Relations.SetOperator.UNION,
                Relations.DuplicatePolicy.ALL, Collections.singletonList(mapping));
        assertEquals(Relations.DuplicatePolicy.ALL, union.getDuplicatePolicy());
        assertSame(mapping, union.getMappings().get(0));
        assertEquals(new SlotId("left.id"), mapping.getBranchSlots().get(0).getSlot().get());
        assertFalse(mapping.getBranchSlots().get(1).getSlot().isPresent());
        assertEquals("BRANCH_SCHEMA_UNKNOWN", mapping.getBranchSlots().get(1).getDiagnosticCode().get());
    }

    @Test
    public void transparentMappingCanReuseOneSlotDefinition() {
        OutputSlot slot = IrTestBuilder.slot("scan.id", "id");
        Relations.Scan scan = IrTestBuilder.scan("scan", "instance", "table", OutputSchema.known(Collections.singletonList(slot)));
        BoundExpression predicate = new Expressions.Literal(new NodeId("true"), TypeRef.unknown(), null,
                new Expressions.LiteralValue(Expressions.LiteralValue.Kind.BOOLEAN, "true"));
        SlotMapping reuse = new SlotMapping(slot.getId(), slot.getId());
        Relations.Filter filter = new Relations.Filter(new NodeId("filter"), scan.getOutputSchema(), new QueryBlockId("main"), 1, null,
                scan.getId(), predicate, Collections.singletonList(reuse));
        assertSame(scan.getOutputSchema(), filter.getOutputSchema());
        assertEquals(reuse.getInput(), reuse.getOutput());
    }

    @Test(expected = IllegalArgumentException.class)
    public void windowBoundRequiresExplicitOffsetOnlyForFiniteEdges() {
        new Relations.FrameBound(Relations.FrameBoundKind.PRECEDING, null);
    }
}
