package com.panfan.hivelineage.v2.ir.expression;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.ScopeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

public class ExpressionContractTest {
    @Test
    public void schemaFreePhysicalHandlesKeepDifferentOwnersForSelfJoin() {
        FieldIdentity field = new FieldIdentity("orders.id", "id");
        QualifiedFieldHandle left = new QualifiedFieldHandle(new NodeId("left.ref"), TypeRef.unknown(), null,
                new RelationInstanceId("orders.left"), field, Collections.<String>emptyList(),
                QualifiedFieldHandle.ExistenceState.UNVERIFIED);
        QualifiedFieldHandle right = new QualifiedFieldHandle(new NodeId("right.ref"), TypeRef.unknown(), null,
                new RelationInstanceId("orders.right"), field, Collections.<String>emptyList(),
                QualifiedFieldHandle.ExistenceState.UNVERIFIED);
        assertNotEquals(left.getOwner(), right.getOwner());
        assertEquals(left.getFieldIdentity(), right.getFieldIdentity());
        assertEquals(QualifiedFieldHandle.ExistenceState.UNVERIFIED, left.getExistence());
        assertEquals(TypeRef.Kind.UNKNOWN, left.getType().getKind());
        assertFalse(left.getSourceRef().isPresent());
    }

    @Test
    public void opaqueCallAndUnknownExpressionRetainProvedArgumentsWithoutTextOrProvider() {
        Expressions.SlotRef argument = new Expressions.SlotRef(new NodeId("argument"), TypeRef.unknown(), null,
                new SlotId("input.id"), Collections.singletonList("nested"));
        List<BoundExpression> arguments = new ArrayList<BoundExpression>(Collections.<BoundExpression>singletonList(argument));
        OpaqueFunctionSymbol function = new OpaqueFunctionSymbol(new QualifiedName(Arrays.asList("custom", "f")), "OPAQUE_FUNCTION");
        Expressions.Call call = new Expressions.Call(new NodeId("call"), TypeRef.unknown(), null, function, arguments, false);
        Expressions.UnknownExpression unknown = new Expressions.UnknownExpression(new NodeId("unknown"), TypeRef.unknown(), null,
                arguments, "UNSUPPORTED_EXPRESSION");
        arguments.clear();
        assertEquals(FunctionSymbol.FunctionKind.OPAQUE, call.getFunction().getKind());
        assertSame(argument, call.getArguments().get(0));
        assertSame(argument, unknown.getKnownChildren().get(0));
        assertEquals("UNSUPPORTED_EXPRESSION", unknown.getDiagnosticCode());
    }

    @Test
    public void subqueryAndCorrelationUseBoundGraphAndOuterScopeIdentities() {
        Expressions.CorrelatedSlotRef outer = new Expressions.CorrelatedSlotRef(new NodeId("outerRef"), TypeRef.unknown(), null,
                new ScopeId("main.scope"), new SlotId("main.id"), Collections.<String>emptyList());
        Expressions.InSubquery in = new Expressions.InSubquery(new NodeId("in"), TypeRef.unknown(), null,
                outer, new NodeId("subquery.root"), new SlotId("subquery.id"), true);
        assertSame(outer, in.getValue());
        assertEquals(new ScopeId("main.scope"), outer.getOuterScopeId());
        assertEquals(new NodeId("subquery.root"), in.getQueryRoot());
        assertEquals(new SlotId("subquery.id"), in.getResultSlot());
    }
}
