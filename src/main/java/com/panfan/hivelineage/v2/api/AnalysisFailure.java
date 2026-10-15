package com.panfan.hivelineage.v2.api;

/** Expected stage rejection, distinct from unexpected implementation failures. */
final class AnalysisFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final String code;

    AnalysisFailure(com.panfan.hivelineage.v2.common.DiagnosticCode code, String message) {
        this(code.getValue(), message);
    }

    AnalysisFailure(String code, String message) {
        super(message);
        this.code = code;
    }
}
