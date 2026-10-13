package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import java.util.Objects;

/** Stable display path declared by binding structure, independent of opaque identities. */
public final class BoundQueryBlockReference {
    private final QueryBlockId id;
    private final String path;
    private final boolean analyzeJoins;

    public BoundQueryBlockReference(QueryBlockId id, String path) {
        this(id, path, true);
    }

    public BoundQueryBlockReference(QueryBlockId id, String path, boolean analyzeJoins) {
        this.id = Objects.requireNonNull(id, "id");
        this.path = Objects.requireNonNull(path, "path");
        this.analyzeJoins = analyzeJoins;
        if (path.trim().isEmpty()) {
            throw new IllegalArgumentException("query block path must not be blank");
        }
    }

    public boolean isAnalyzeJoins() {
        return analyzeJoins;
    }

    public QueryBlockId getId() {
        return id;
    }

    public String getPath() {
        return path;
    }
}
