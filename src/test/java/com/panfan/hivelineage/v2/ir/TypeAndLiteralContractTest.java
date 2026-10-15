package com.panfan.hivelineage.v2.ir;

import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.FieldId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

public class TypeAndLiteralContractTest {
    @Test
    public void structuredTypesFreezeFieldsWithoutInterpretingHiveSyntax() {
        TypeRef element = TypeRef.unknown();
        List<TypeRef.StructField> fields = new ArrayList<TypeRef.StructField>();
        fields.add(new TypeRef.StructField("value", element));
        TypeRef struct = new TypeRef(TypeRef.Kind.STRUCT, "record", Collections.<TypeRef>emptyList(), fields);
        fields.clear();
        assertEquals(TypeRef.Kind.STRUCT, struct.getKind());
        assertSame(element, struct.getFields().get(0).getType());
        assertEquals("value", struct.getFields().get(0).getName());
    }

    @Test
    public void displayNameChangesDoNotChangeFieldIdentity() {
        FieldIdentity first = new FieldIdentity(new FieldId("stable"), "before");
        FieldIdentity second = new FieldIdentity(new FieldId("stable"), "after");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void nullLiteralIsAnExplicitValueNotAnUnknownReference() {
        Expressions.LiteralValue literal = new Expressions.LiteralValue(Expressions.LiteralValue.Kind.NULL, null);
        assertEquals(Expressions.LiteralValue.Kind.NULL, literal.getKind());
        assertFalse(literal.getValue().isPresent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonNullLiteralCannotLoseItsValue() {
        new Expressions.LiteralValue(Expressions.LiteralValue.Kind.STRING, null);
    }
}
