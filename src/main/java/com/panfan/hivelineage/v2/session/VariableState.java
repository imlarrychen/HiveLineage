package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.Optional;

/**
 * A fallback value is available text, but remains distinct from a known business value.
 */
public final class VariableState {
    public enum Resolution {
        KNOWN,
        FALLBACK,
        UNRESOLVED,
        NOT_REQUESTED
    }

    private final Resolution resolution;
    private final Optional<String> value;
    private final SourceRef source;

    public VariableState(Resolution resolution, Optional<String> value, SourceRef source) {
        this.resolution = ModelChecks.value(resolution, "resolution");
        this.value = ModelChecks.value(value, "value");
        this.source = ModelChecks.value(source, "source");
        boolean needsValue = resolution == Resolution.KNOWN || resolution == Resolution.FALLBACK;
        if (needsValue != value.isPresent()) {
            throw new IllegalArgumentException("only KNOWN and FALLBACK must contain a value");
        }
    }

    public Resolution getResolution() {
        return resolution;
    }

    public Optional<String> getValue() {
        return value;
    }

    public SourceRef getSource() {
        return source;
    }
}

