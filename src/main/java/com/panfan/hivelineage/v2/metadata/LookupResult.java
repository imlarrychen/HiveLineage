package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

/**
 * Immutable result distinguishing lack of knowledge, authority and access failures.
 */
public final class LookupResult<T> {
    private final LookupStatus status;
    private final Optional<T> value;
    private final DiagnosticBag diagnostics;

    public LookupResult(LookupStatus status, Optional<T> value, DiagnosticBag diagnostics) {
        this.status = ModelChecks.value(status, "status");
        this.value = ModelChecks.value(value, "value");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        if ((status == LookupStatus.FOUND) != value.isPresent()) {
            throw new IllegalArgumentException("only FOUND must contain a value");
        }
    }

    public static <T> LookupResult<T> found(T value) {
        return found(value, DiagnosticBag.empty());
    }

    public static <T> LookupResult<T> found(T value, DiagnosticBag diagnostics) {
        return new LookupResult<>(LookupStatus.FOUND, Optional.of(ModelChecks.value(value, "value")), diagnostics);
    }

    public static <T> LookupResult<T> unknown(DiagnosticBag diagnostics) {
        return absent(LookupStatus.UNKNOWN, diagnostics);
    }

    public static <T> LookupResult<T> notFound(DiagnosticBag diagnostics) {
        return absent(LookupStatus.NOT_FOUND, diagnostics);
    }

    public static <T> LookupResult<T> capabilityMissing(DiagnosticBag diagnostics) {
        return absent(LookupStatus.CAPABILITY_MISSING, diagnostics);
    }

    public static <T> LookupResult<T> accessError(DiagnosticBag diagnostics) {
        return absent(LookupStatus.ACCESS_ERROR, diagnostics);
    }

    private static <T> LookupResult<T> absent(LookupStatus status, DiagnosticBag diagnostics) {
        return new LookupResult<>(status, Optional.<T>empty(), diagnostics);
    }

    public LookupStatus getStatus() {
        return status;
    }

    public Optional<T> getValue() {
        return value;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}

