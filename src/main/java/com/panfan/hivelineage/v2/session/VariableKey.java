package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import java.util.Objects;

/** Namespace is explicit; lookup precedence is outside this immutable identity. */
public final class VariableKey {
    public enum Namespace {
        UNQUALIFIED,
        HIVEVAR,
        HIVECONF,
        RUNTIME,
        SYSTEM,
        ENVIRONMENT
    }

    private final Namespace namespace;
    private final String name;

    public VariableKey(Namespace namespace, String name) {
        this.namespace = ModelChecks.value(namespace, "namespace");
        this.name = ModelChecks.text(name, "name");
    }

    public Namespace getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VariableKey)) {
            return false;
        }
        VariableKey that = (VariableKey) other;
        return namespace == that.namespace && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, name);
    }
}

