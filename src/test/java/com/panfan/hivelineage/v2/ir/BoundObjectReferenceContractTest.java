package com.panfan.hivelineage.v2.ir;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class BoundObjectReferenceContractTest {
    @Test
    public void opaqueObjectAndFieldIdsKeepDisplayNamesInImmutableDeclarations() {
        ObjectId objectId = new ObjectId("opaque-object-7");
        FieldIdentity field = new FieldIdentity("opaque-field-9", "order_id");
        List<FieldIdentity> fields = new ArrayList<FieldIdentity>(Collections.singletonList(field));
        BoundObjectReference reference = new BoundObjectReference(objectId,
                new QualifiedName(Arrays.asList("warehouse", "orders")), BoundObjectReference.ObjectKind.TABLE,
                SchemaState.KNOWN, fields);
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<ObjectId, BoundObjectReference>();
        objects.put(objectId, reference);
        BoundAnalysisUnit empty = IrTestBuilder.unit(Collections.<BoundRelation>emptyList());
        BoundAnalysisUnit unit = new BoundAnalysisUnit(empty.getStatementId(), empty.getRelations(), empty.getQueryResults(),
                empty.getWrites(), empty.getEffects(), empty.getBindingCoverage(), empty.getDiagnostics(), objects);
        fields.clear();
        objects.clear();
        assertSame(reference, unit.getObjects().get(objectId));
        assertEquals("warehouse.orders", reference.getName().getDisplayName());
        assertEquals("order_id", reference.getFields().get(0).getDisplayName());
        assertEquals("opaque-field-9", reference.getFields().get(0).getValue());
        assertEquals(SchemaState.KNOWN, reference.getSchemaState());
        try {
            unit.getObjects().clear();
            fail("object declarations must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, unit.getObjects().size());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void declarationIndexCannotAssignNameToAnotherIdentity() {
        BoundAnalysisUnit empty = IrTestBuilder.unit(Collections.<BoundRelation>emptyList());
        BoundObjectReference reference = new BoundObjectReference(new ObjectId("actual"),
                new QualifiedName(Arrays.asList("warehouse", "orders")), BoundObjectReference.ObjectKind.TABLE);
        new BoundAnalysisUnit(empty.getStatementId(), empty.getRelations(), empty.getQueryResults(),
                empty.getWrites(), empty.getEffects(), empty.getBindingCoverage(), empty.getDiagnostics(),
                Collections.singletonMap(new ObjectId("different"), reference));
    }
}
