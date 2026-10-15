package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

/**
 * Unknown-width output gap, not a synthetic star column.
 */
public final class UnknownOutputSegment implements OutputSegment {
    private final NodeId id;
    private final String diagnosticCode;

    public UnknownOutputSegment(NodeId id, String diagnosticCode) {
        this.id = Objects.requireNonNull(id, "id");
        this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }

    public NodeId getId() {
        return id;
    }

    public String getDiagnosticCode() {
        return diagnosticCode;
    }
}
