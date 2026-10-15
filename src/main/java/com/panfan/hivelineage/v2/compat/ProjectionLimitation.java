package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

public final class ProjectionLimitation {
    private final String code;
    private final String message;
    private final Optional<StatementIdentity> statement;

    public ProjectionLimitation(String code, String message, Optional<StatementIdentity> statement) {
        this.code = ModelChecks.text(code, "code");
        this.message = ModelChecks.text(message, "message");
        this.statement = ModelChecks.value(statement, "statement");
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Optional<StatementIdentity> getStatement() {
        return statement;
    }
}
