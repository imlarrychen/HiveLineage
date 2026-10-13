package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class RuleKey {
    private final String namespace;
    private final String operation;
    private final String version;

    public RuleKey(String namespace, String operation, String version) {
        this.namespace = ModelChecks.text(namespace, "namespace");
        this.operation = ModelChecks.text(operation, "operation");
        this.version = ModelChecks.text(version, "version");
    }

    public String getNamespace() {
        return namespace;
    }

    public String getOperation() {
        return operation;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RuleKey)) {
            return false;
        }
        RuleKey that = (RuleKey) other;
        return namespace.equals(that.namespace) && operation.equals(that.operation) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(namespace, operation, version);
    }
}
