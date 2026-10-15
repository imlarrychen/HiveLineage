package com.panfan.hivelineage.v2.ir.quality;

import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

/**
 * Explicit skipped region; never stores syntax, AST, token buffers or callbacks.
 */
public final class NotRequestedRegion {
    public enum Kind {PROJECTION, OUTPUT_SCHEMA, SINK_MAPPING, WINDOW, DEFINITION, DATASET_VERSION, SESSION_EFFECT}

    private final NodeId id;
    private final Kind kind;

    public NotRequestedRegion(NodeId id, Kind kind) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public NodeId getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }
}
