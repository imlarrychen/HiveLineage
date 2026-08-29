package com.panfan.hivelineage.core;

final class FallbackJoinSource {
    enum Kind {
        PHYSICAL,
        CTE,
        SUBQUERY,
        TEMPORARY
    }

    private final String qualifiedName;
    private final String queryBlockPath;
    private final int startOffset;
    private final Kind kind;

    FallbackJoinSource(
        String qualifiedName,
        String queryBlockPath,
        int startOffset,
        Kind kind
    ) {
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            throw new IllegalArgumentException("qualifiedName must not be empty");
        }
        if (queryBlockPath == null || queryBlockPath.isEmpty()) {
            throw new IllegalArgumentException("queryBlockPath must not be empty");
        }
        if (startOffset < 0 || kind == null) {
            throw new IllegalArgumentException("source position and kind are required");
        }
        this.qualifiedName = qualifiedName;
        this.queryBlockPath = queryBlockPath;
        this.startOffset = startOffset;
        this.kind = kind;
    }

    String getQualifiedName() {
        return qualifiedName;
    }

    String getQueryBlockPath() {
        return queryBlockPath;
    }

    int getStartOffset() {
        return startOffset;
    }

    Kind getKind() {
        return kind;
    }

    boolean isPhysical() {
        return kind == Kind.PHYSICAL;
    }
}
