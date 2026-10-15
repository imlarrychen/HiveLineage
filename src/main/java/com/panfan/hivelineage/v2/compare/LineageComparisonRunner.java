package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.metadata.MetadataSnapshot;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;

public interface LineageComparisonRunner {
    ComparisonResult compare(ScriptAnalysisRequest request, WorkflowContext workflow, MetadataSnapshot snapshot);
}
