package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.session.InitialSessionContext;

public final class ScriptAnalysisRequest {
    private final TaskIdentity task;
    private final SourceDocument source;
    private final DialectProfile dialect;
    private final InitialSessionContext initialSession;
    private final MetadataReadContext metadataContext;
    private final AnalysisContract contract;
    private final ResourceLimits resourceLimits;

    public ScriptAnalysisRequest(TaskIdentity task, SourceDocument source, DialectProfile dialect, InitialSessionContext initialSession, MetadataReadContext metadataContext, AnalysisContract contract, ResourceLimits resourceLimits) {
        this.task = ModelChecks.value(task, "task");
        this.source = ModelChecks.value(source, "source");
        this.dialect = ModelChecks.value(dialect, "dialect");
        this.initialSession = ModelChecks.value(initialSession, "initialSession");
        this.metadataContext = ModelChecks.value(metadataContext, "metadataContext");
        this.contract = ModelChecks.value(contract, "contract");
        this.resourceLimits = ModelChecks.value(resourceLimits, "resourceLimits");
    }

    public TaskIdentity getTask() {
        return task;
    }

    public SourceDocument getSource() {
        return source;
    }

    public DialectProfile getDialect() {
        return dialect;
    }

    public InitialSessionContext getInitialSession() {
        return initialSession;
    }

    public MetadataReadContext getMetadataContext() {
        return metadataContext;
    }

    public AnalysisContract getContract() {
        return contract;
    }

    public ResourceLimits getResourceLimits() {
        return resourceLimits;
    }
}
