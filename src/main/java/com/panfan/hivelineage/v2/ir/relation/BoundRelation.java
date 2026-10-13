package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import java.util.List;
import java.util.Optional;

/** Pure relation structure; no parser/provider handles or precomputed lineage. */
public interface BoundRelation {
    NodeId getId();
    OutputSchema getOutputSchema();
    List<NodeId> getInputIds();
    QueryBlockId getQueryBlockId();
    int getSourceOrder();
    Optional<SourceRef> getSourceRef();
}
