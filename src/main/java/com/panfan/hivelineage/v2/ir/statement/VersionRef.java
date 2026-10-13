package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import java.util.Objects;
import java.util.Optional;

/** Missing version demand differs from an unknown requested data version. */
public final class VersionRef {
    public enum State { NOT_REQUESTED, KNOWN, UNKNOWN }
    private final State state;
    private final DatasetVersionId id;
    private final String diagnosticCode;

    private VersionRef(State state, DatasetVersionId id, String diagnosticCode) {
        this.state = state;
        this.id = id;
        this.diagnosticCode = diagnosticCode;
    }

    public static VersionRef notRequested() {
        return new VersionRef(State.NOT_REQUESTED, null, null);
    }

    public static VersionRef known(DatasetVersionId id) {
        return new VersionRef(State.KNOWN, Objects.requireNonNull(id, "id"), null);
    }

    public static VersionRef unknown(String diagnosticCode) {
        return new VersionRef(State.UNKNOWN, null, Objects.requireNonNull(diagnosticCode, "diagnosticCode"));
    }

    public State getState() {
        return state;
    }

    public Optional<DatasetVersionId> getId() {
        return Optional.ofNullable(id);
    }

    public Optional<String> getDiagnosticCode() {
        return Optional.ofNullable(diagnosticCode);
    }
}
