package com.panfan.hivelineage.core;

import org.junit.Assert;
import org.junit.Test;

public class QualifiedColumnReferenceTest {

    @Test
    public void parsesOneToThreePartColumnReferences() {
        assertReference("field1", null, "field1", false);
        assertReference("a.field1", "a", "field1", false);
        assertReference("whc.a.field1", "whc.a", "field1", false);
    }

    @Test
    public void parsesBareAndQualifiedStars() {
        assertReference("*", null, "*", true);
        assertReference("a.*", "a", "*", true);
        assertReference("whc.a.*", "whc.a", "*", true);
    }

    @Test
    public void normalizesReferencesToLowerCase() {
        assertReference("WHC.A.FIELD1", "whc.a", "field1", false);
    }

    @Test
    public void rejectsFourPartReferencesAndExpressions() {
        Assert.assertNull(QualifiedColumnReference.parse("catalog.whc.a.field1"));
        Assert.assertNull(QualifiedColumnReference.parse("sum(a.id)"));
    }

    private void assertReference(String text, String owner, String column, boolean star) {
        QualifiedColumnReference reference = QualifiedColumnReference.parse(text);

        Assert.assertNotNull(reference);
        Assert.assertEquals(owner, reference.getOwner());
        Assert.assertEquals(column, reference.getColumn());
        Assert.assertEquals(star, reference.isStar());
    }
}
