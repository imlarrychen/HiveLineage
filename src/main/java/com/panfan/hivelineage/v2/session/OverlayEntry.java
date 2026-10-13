package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import java.util.Optional;

/** Permanent tombstones suppress fallback; absence of an overlay does not do so. */
public final class OverlayEntry {
    public enum State {
        UPSERTED,
        DELETED,
        UNKNOWN
    }

    private final State state;
    private final Optional<CatalogEntry> entry;
    private final DiagnosticBag diagnostics;

    public OverlayEntry(State state, Optional<CatalogEntry> entry, DiagnosticBag diagnostics) {
        this.state = ModelChecks.value(state, "state");
        this.entry = ModelChecks.value(entry, "entry");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        if ((state == State.UPSERTED) != entry.isPresent()) {
            throw new IllegalArgumentException("only UPSERTED must contain a catalog entry");
        }
    }

    public static OverlayEntry upserted(CatalogEntry entry) {
        return new OverlayEntry(State.UPSERTED, Optional.of(ModelChecks.value(entry, "entry")), DiagnosticBag.empty());
    }

    public static OverlayEntry deleted() {
        return new OverlayEntry(State.DELETED, Optional.<CatalogEntry>empty(), DiagnosticBag.empty());
    }

    public static OverlayEntry unknown(DiagnosticBag diagnostics) {
        return new OverlayEntry(State.UNKNOWN, Optional.<CatalogEntry>empty(), diagnostics);
    }

    public State getState() {
        return state;
    }

    public Optional<CatalogEntry> getEntry() {
        return entry;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}

