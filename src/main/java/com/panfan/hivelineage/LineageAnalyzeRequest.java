package com.panfan.hivelineage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class LineageAnalyzeRequest {
    private final String workflowId;
    private final String taskId;
    private final String taskVersion;
    private final String clusterName;
    private final String defaultDatabase;
    private final String rawScript;
    private final Map<String, String> runtimeVariables;
    private final LineageAnalyzeOptions options;
    private final HiveMetadataProvider metadataProvider;
    private final HiveMetadataCache metadataCache;

    private LineageAnalyzeRequest(Builder builder) {
        this.workflowId = builder.workflowId;
        this.taskId = builder.taskId;
        this.taskVersion = builder.taskVersion;
        this.clusterName = builder.clusterName == null ? "default" : builder.clusterName;
        this.defaultDatabase = builder.defaultDatabase;
        this.rawScript = builder.rawScript;
        this.runtimeVariables = Collections.unmodifiableMap(new HashMap<>(builder.runtimeVariables));
        this.options = builder.options == null ? LineageAnalyzeOptions.builder().build() : builder.options;
        this.metadataProvider = builder.metadataProvider;
        this.metadataCache = builder.metadataCache == null ? new HiveMetadataCache() : builder.metadataCache;
    }

    public static Builder builder() {
        return new Builder();
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

    public String getDefaultDatabase() {
        return defaultDatabase;
    }

    public String getRawScript() {
        return rawScript;
    }

    public Map<String, String> getRuntimeVariables() {
        return runtimeVariables;
    }

    public LineageAnalyzeOptions getOptions() {
        return options;
    }

    public HiveMetadataProvider getMetadataProvider() {
        return metadataProvider;
    }

    public HiveMetadataCache getMetadataCache() {
        return metadataCache;
    }

    public static final class Builder {
        private String workflowId;
        private String taskId;
        private String taskVersion;
        private String clusterName = "default";
        private String defaultDatabase;
        private String rawScript;
        private Map<String, String> runtimeVariables = new HashMap<>();
        private LineageAnalyzeOptions options;
        private HiveMetadataProvider metadataProvider;
        private HiveMetadataCache metadataCache;

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskVersion(String taskVersion) {
            this.taskVersion = taskVersion;
            return this;
        }

        public Builder clusterName(String clusterName) {
            this.clusterName = clusterName;
            return this;
        }

        public Builder defaultDatabase(String defaultDatabase) {
            this.defaultDatabase = defaultDatabase;
            return this;
        }

        public Builder rawScript(String rawScript) {
            this.rawScript = rawScript;
            return this;
        }

        public Builder runtimeVariables(Map<String, String> runtimeVariables) {
            this.runtimeVariables = runtimeVariables == null ? new HashMap<>() : new HashMap<>(runtimeVariables);
            return this;
        }

        public Builder options(LineageAnalyzeOptions options) {
            this.options = options;
            return this;
        }

        public Builder metadataProvider(HiveMetadataProvider metadataProvider) {
            this.metadataProvider = metadataProvider;
            return this;
        }

        public Builder metadataCache(HiveMetadataCache metadataCache) {
            this.metadataCache = metadataCache;
            return this;
        }

        public LineageAnalyzeRequest build() {
            return new LineageAnalyzeRequest(this);
        }
    }
}
