package com.panfan.hivelineage.v2.ir.quality;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

/** Uncertain interval; known assignments remain separate and are never guessed across it. */
public final class UnknownMapping {
    public enum Reason { SOURCE_WIDTH_UNKNOWN, TARGET_SCHEMA_UNKNOWN, AMBIGUOUS_FIELD, INVALID_COLUMNS, UNSUPPORTED_RULE }
    private final NodeId regionId;
    private final Reason reason;
    private final String diagnosticCode;

    public UnknownMapping(NodeId regionId, Reason reason, String diagnosticCode) {
        this.regionId = Objects.requireNonNull(regionId, "regionId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.diagnosticCode = Objects.requireNonNull(diagnosticCode, "diagnosticCode");
    }

    public NodeId getRegionId() {
        return regionId;
    }

    public Reason getReason() {
        return reason;
    }

    public String getDiagnosticCode() {
        return diagnosticCode;
    }
}
