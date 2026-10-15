package com.panfan.hivelineage.v2.ir;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Manual model fixture. It neither parses SQL nor verifies a graph.
 */
public final class IrTestBuilder {
    private IrTestBuilder() {
    }

    public static OutputSlot slot(String id, String displayName) {
        return new OutputSlot(new SlotId(id), displayName, TypeRef.unknown(), null);
    }

    public static Relations.Scan scan(String id, String instance, String object, OutputSchema schema) {
        return new Relations.Scan(new NodeId(id), schema, new QueryBlockId("main"), 0, null,
                new ObjectId(object), VersionRef.notRequested(), new RelationInstanceId(instance));
    }

    public static BoundAnalysisUnit unit(List<BoundRelation> relations) {
        return new BoundAnalysisUnit(new NodeId("statement"), relations, Collections.<QueryResult>emptyList(),
                Collections.<BoundWrite>emptyList(), Collections.<BoundEffect>emptyList(),
                new BindingCoverage(Collections.<NodeId, Map<BindingRequirement, BindingCoverage.State>>emptyMap()),
                new DiagnosticBag(Collections.<Diagnostic>emptyList()));
    }
}
