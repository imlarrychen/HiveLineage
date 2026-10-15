package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

public final class Difference {
    private final String caseId;
    private final Optional<StatementIdentity> statement;
    private final DifferenceKind kind;
    private final String description;
    private final DifferenceDisposition disposition;

    public Difference(String caseId, Optional<StatementIdentity> statement, DifferenceKind kind, String description, DifferenceDisposition disposition) {
        this.caseId = ModelChecks.text(caseId, "caseId");
        this.statement = ModelChecks.value(statement, "statement");
        this.kind = ModelChecks.value(kind, "kind");
        this.description = ModelChecks.text(description, "description");
        this.disposition = ModelChecks.value(disposition, "disposition");
    }

    public String getCaseId() {
        return caseId;
    }

    public Optional<StatementIdentity> getStatement() {
        return statement;
    }

    public DifferenceKind getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public DifferenceDisposition getDisposition() {
        return disposition;
    }
}
