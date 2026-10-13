package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class SetCommandSyntax extends CommandSyntax {
    private final String namespace;
    private final String key;
    private final String value;

    public SetCommandSyntax(String namespace, String key, String value) {
        super();
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.key = Objects.requireNonNull(key, "key");
        this.value = value;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }
}
