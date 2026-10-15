package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

public final class MetadataVersion {
    public enum State {
        KNOWN,
        UNKNOWN,
        NOT_REQUESTED
    }

    private final State state;
    private final Optional<String> value;

    public MetadataVersion(State state, Optional<String> value) {
        this.state = ModelChecks.value(state, "state");
        this.value = ModelChecks.value(value, "value");
        if ((state == State.KNOWN) != value.isPresent()) {
            throw new IllegalArgumentException("only KNOWN must contain a version");
        }
        if (value.isPresent()) {
            ModelChecks.text(value.get(), "version");
        }
    }

    public static MetadataVersion known(String value) {
        return new MetadataVersion(State.KNOWN, Optional.of(ModelChecks.text(value, "version")));
    }

    public static MetadataVersion unknown() {
        return new MetadataVersion(State.UNKNOWN, Optional.<String>empty());
    }

    public static MetadataVersion notRequested() {
        return new MetadataVersion(State.NOT_REQUESTED, Optional.<String>empty());
    }

    public State getState() {
        return state;
    }

    public Optional<String> getValue() {
        return value;
    }
}

