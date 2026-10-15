package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;

import java.util.Objects;
import java.util.Optional;

/**
 * Display-only unresolved reference; it is never a physical field assertion.
 */
public final class UnknownDirectEndpoint implements LineageEndpoint {
    private final Optional<RelationInstanceId> owner;
    private final QualifiedName displayName;
    private final DiagnosticBag diagnostics;

    public UnknownDirectEndpoint(Optional<RelationInstanceId> owner, QualifiedName displayName, DiagnosticBag diagnostics) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public Optional<RelationInstanceId> getOwner() {
        return owner;
    }

    public QualifiedName getDisplayName() {
        return displayName;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.UNKNOWN;
    }
}
