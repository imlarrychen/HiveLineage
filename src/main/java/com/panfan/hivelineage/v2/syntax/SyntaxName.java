package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class SyntaxName {
    private final List<IdentifierPart> parts;

    public SyntaxName(List<IdentifierPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("name must contain at least one part");
        }
        this.parts = ModelCollections.immutableList(parts);
    }

    public List<IdentifierPart> getParts() {
        return parts;
    }
}
