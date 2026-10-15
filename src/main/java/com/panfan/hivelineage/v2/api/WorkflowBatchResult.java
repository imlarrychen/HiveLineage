package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.metadata.MetadataReadManifest;
import java.util.List;

public final class WorkflowBatchResult {
    private final String workflowId;
    private final List<ScriptAnalysisResult> tasks;
    private final MetadataReadManifest metadataReads;
    private final DiagnosticBag diagnostics;

    public WorkflowBatchResult(String workflowId, List<ScriptAnalysisResult> tasks, MetadataReadManifest metadataReads, DiagnosticBag diagnostics) {
        this.workflowId = ModelChecks.text(workflowId, "workflowId");
        this.tasks = ModelCollections.immutableList(tasks);
        this.metadataReads = ModelChecks.value(metadataReads, "metadataReads");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public List<ScriptAnalysisResult> getTasks() {
        return tasks;
    }

    public MetadataReadManifest getMetadataReads() {
        return metadataReads;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
