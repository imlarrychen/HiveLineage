package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.v2.api.V2AnalyzeRequest;
import com.panfan.hivelineage.v2.metadata.MetadataSnapshot;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;

public interface LineageComparisonRunner {
    ComparisonResult compare(V2AnalyzeRequest request, WorkflowContext workflow, MetadataSnapshot snapshot);
}
