package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.SourceRef;
import java.util.Objects;

public final class VariableEvidence {
    private final String namespace;
    private final String name;
    private final String replacement;
    private final VariableResolutionKind resolution;
    private final SourceRef sourceRef;

    public VariableEvidence(
            String namespace,
            String name,
            String replacement,
            VariableResolutionKind resolution,
            SourceRef sourceRef) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.name = Objects.requireNonNull(name, "name");
        this.replacement = replacement;
        this.resolution = Objects.requireNonNull(resolution, "resolution");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getReplacement() {
        return replacement;
    }

    public VariableResolutionKind getResolution() {
        return resolution;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }
}
