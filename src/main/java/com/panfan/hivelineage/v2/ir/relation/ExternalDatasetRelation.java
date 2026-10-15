package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;

import java.util.Collections;

/**
 * Declared storage input. Its existence, contents and ordered fields are not inferred.
 */
public final class ExternalDatasetRelation extends Relations.Base {
    public enum SourceKind {LOCAL_PATH, DISTRIBUTED_PATH}

    private final String datasetIdentity;
    private final SourceKind sourceKind;

    public ExternalDatasetRelation(NodeId id, OutputSchema output, QueryBlockId block, int sourceOrder,
                                   SourceRef source, String datasetIdentity, SourceKind sourceKind) {
        super(id, output, block, sourceOrder, source, Collections.<NodeId>emptyList());
        this.datasetIdentity = ModelChecks.text(datasetIdentity, "datasetIdentity");
        this.sourceKind = ModelChecks.value(sourceKind, "sourceKind");
    }

    public String getDatasetIdentity() {
        return datasetIdentity;
    }

    public SourceKind getSourceKind() {
        return sourceKind;
    }
}
