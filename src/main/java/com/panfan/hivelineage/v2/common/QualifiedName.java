package com.panfan.hivelineage.v2.common;

import java.util.List;

/**
 * Structured name preserving parts; dialect normalization belongs to binding.
 */
public final class QualifiedName {
    private final List<String> parts;

    public QualifiedName(List<String> parts) {
        this.parts = ModelCollections.immutableList(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("name must contain at least one part");
        }
        for (String part : this.parts) {
            ModelChecks.text(part, "name part");
        }
    }

    public List<String> getParts() {
        return parts;
    }

    public String getDisplayName() {
        return String.join(".", parts);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QualifiedName && parts.equals(((QualifiedName) other).parts);
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
