package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.Map;
import java.util.Optional;

/** Request-owned initial state; never changes JVM environment or shared workflow state. */
public final class InitialSessionContext {
    private final String sessionId;
    private final String workflowId;
    private final String taskId;
    private final String taskVersion;
    private final String clusterName;
    private final Optional<QualifiedName> defaultDatabase;
    private final Map<VariableKey, VariableState> initialVariables;
    private final Map<String, String> semanticConfiguration;

    public InitialSessionContext(String sessionId, String workflowId, String taskId,
                                 String taskVersion, String clusterName,
                                 Optional<QualifiedName> defaultDatabase,
                                 Map<VariableKey, VariableState> initialVariables,
                                 Map<String, String> semanticConfiguration) {
        this.sessionId = ModelChecks.text(sessionId, "sessionId");
        this.workflowId = ModelChecks.text(workflowId, "workflowId");
        this.taskId = ModelChecks.text(taskId, "taskId");
        this.taskVersion = ModelChecks.text(taskVersion, "taskVersion");
        this.clusterName = ModelChecks.text(clusterName, "clusterName");
        this.defaultDatabase = ModelChecks.value(defaultDatabase, "defaultDatabase");
        this.initialVariables = ModelCollections.immutableMap(initialVariables);
        this.semanticConfiguration = ModelCollections.immutableMap(semanticConfiguration);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskVersion() {
        return taskVersion;
    }

    public String getClusterName() {
        return clusterName;
    }

    public Optional<QualifiedName> getDefaultDatabase() {
        return defaultDatabase;
    }

    public Map<VariableKey, VariableState> getInitialVariables() {
        return initialVariables;
    }

    public Map<String, String> getSemanticConfiguration() {
        return semanticConfiguration;
    }
}

