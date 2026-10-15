package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.DiagnosticCode;

/** Codes owned by stage coordination; dialect and semantic codes stay with their stages. */
public enum PipelineDiagnosticCode implements DiagnosticCode {
    RESOURCE_LIMIT("RESOURCE_LIMIT"),
    VARIABLE_EXPANSION_FAILED("VARIABLE_EXPANSION_FAILED"),
    SQL_PARSE_FAILED("SQL_PARSE_FAILED"),
    INVALID_USE_DATABASE("INVALID_USE_DATABASE"),
    UNSUPPORTED_STATEMENT("UNSUPPORTED_STATEMENT"),
    BINDING_FAILED("BINDING_FAILED"),
    IR_VERIFICATION_FAILED("IR_VERIFICATION_FAILED"),
    DEFINITION_PROTOCOL_STALLED("DEFINITION_PROTOCOL_STALLED"),
    VIEW_EXPANSION_LIMIT("VIEW_EXPANSION_LIMIT");

    private final String value;
    PipelineDiagnosticCode(String value) {
        this.value = value;
    }
    @Override public String getValue() {
        return value;
    }
}
