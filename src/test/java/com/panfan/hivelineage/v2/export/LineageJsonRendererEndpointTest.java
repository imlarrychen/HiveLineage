package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.ConstantEndpoint;
import com.panfan.hivelineage.v2.analysis.DirectFieldEndpoint;
import com.panfan.hivelineage.v2.analysis.ExternalDatasetEndpoint;
import com.panfan.hivelineage.v2.analysis.ParameterEndpoint;
import com.panfan.hivelineage.v2.analysis.UnknownDirectEndpoint;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LineageJsonRendererEndpointTest {
    @Test
    public void inspectionRetainsDistinctExternalConstantParameterAndUnresolvedIdentities() {
        String external = LineageJsonRenderer.endpoint(new ExternalDatasetEndpoint("hdfs://host/path", "DISTRIBUTED_PATH"));
        assertTrue(external.contains("hdfs://host/path"));
        assertTrue(external.contains("DISTRIBUTED_PATH"));
        String first = LineageJsonRenderer.endpoint(new ConstantEndpoint("a\"b\n"));
        String second = LineageJsonRenderer.endpoint(new ConstantEndpoint("other"));
        assertFalse(first.equals(second));
        assertTrue(first.contains("a\\\"b\\u000a"));
        assertTrue(LineageJsonRenderer.endpoint(new ParameterEndpoint("hivevar", "x")).contains("hivevar:x"));
        assertTrue(LineageJsonRenderer.endpoint(new UnknownDirectEndpoint(Optional.empty(),
            new QualifiedName(Arrays.asList("a", "missing")), DiagnosticBag.empty())).contains("a.missing"));
    }

    @Test
    public void nestedFieldPathsAndOccurrenceOwnersSurviveStructuredExport() {
        ColumnEndpoint column = new ColumnEndpoint(new ObjectId("object"), new FieldId("field"), Arrays.asList("address", "city"));
        assertTrue(LineageJsonRenderer.endpoint(column).contains("\"fieldPath\":[\"address\",\"city\"]"));
        DirectFieldEndpoint direct = new DirectFieldEndpoint(new RelationInstanceId("self-left"), new FieldId("field"),
            Arrays.asList("city"), Optional.of(new ObjectId("object")));
        String output = LineageJsonRenderer.endpoint(direct);
        assertTrue(output.contains("\"owner\":\"self-left\""));
        assertTrue(output.contains("\"objectId\":\"object\""));
    }

    @Test
    public void opaqueObjectAndFieldIdsRemainSeparateEvenWhenTheirConcatenationMatches() {
        String first = LineageJsonRenderer.endpoint(new ColumnEndpoint(new ObjectId("o"), new FieldId("a:b"), Arrays.asList()));
        String second = LineageJsonRenderer.endpoint(new ColumnEndpoint(new ObjectId("o:a"), new FieldId("b"), Arrays.asList()));
        assertFalse(first.equals(second));
        assertTrue(first.contains("\"objectId\":\"o\""));
        assertTrue(first.contains("\"fieldId\":\"a:b\""));
    }
}
