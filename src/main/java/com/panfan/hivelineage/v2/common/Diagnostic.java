package com.panfan.hivelineage.v2.common;

public final class Diagnostic {
    private final AnalysisStage stage;
    private final String code;
    private final String message;
    private final SourceRef sourceRef;

    public Diagnostic(AnalysisStage stage, String code, String message, SourceRef sourceRef) {
        this.stage = ModelChecks.value(stage, "stage");
        this.code = ModelChecks.text(code, "code");
        this.message = ModelChecks.text(message, "message");
        this.sourceRef = sourceRef;
    }

    public AnalysisStage getStage() {
        return stage;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }
}
