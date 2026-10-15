package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ordered segments retain known prefixes/suffixes without numbering across unknown gaps.
 */
public final class OutputSchema {
    private final SchemaState state;
    private final List<OutputSegment> segments;

    public OutputSchema(SchemaState state, List<OutputSegment> segments) {
        this.state = Objects.requireNonNull(state, "state");
        this.segments = ModelCollections.immutableList(segments);
        if (state == SchemaState.NOT_REQUESTED && !segments.isEmpty()) {
            throw new IllegalArgumentException("unrequested output must have no segments");
        }
        for (OutputSegment segment : segments) {
            if (state == SchemaState.KNOWN && !(segment instanceof KnownOutputSegment)) {
                throw new IllegalArgumentException("known output cannot contain unknown segments");
            }
        }
    }

    public static OutputSchema known(List<OutputSlot> slots) {
        return new OutputSchema(SchemaState.KNOWN, Collections.<OutputSegment>singletonList(new KnownOutputSegment(slots)));
    }

    public static OutputSchema notRequested() {
        return new OutputSchema(SchemaState.NOT_REQUESTED, Collections.<OutputSegment>emptyList());
    }

    public SchemaState getState() {
        return state;
    }

    public List<OutputSegment> getSegments() {
        return segments;
    }
}
