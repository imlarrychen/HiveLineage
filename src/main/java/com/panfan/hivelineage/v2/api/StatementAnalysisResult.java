package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.analysis.JoinFacts;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import java.util.Optional;

public final class StatementAnalysisResult {
    private final StatementIdentity identity;
    private final String statementType;
    private final SourceDocument rawSql;
    private final SourceDocument normalizedSql;
    private final StatementStatus status;
    private final boolean formalLineageProduced;
    private final Optional<LineageFacts> lineage;
    private final Optional<JoinFacts> joins;
    private final Optional<BoundAnalysisUnit> boundUnit;
    private final Optional<String> skipReason;
    private final Optional<String> failureCode;
    private final Optional<String> failureMessage;
    private final DiagnosticBag diagnostics;
    private final Optional<com.panfan.hivelineage.v2.frontend.ExpandedUnit> frontendEvidence;

    public StatementAnalysisResult(StatementIdentity identity, String statementType, SourceDocument rawSql, SourceDocument normalizedSql, StatementStatus status, boolean formalLineageProduced, Optional<LineageFacts> lineage, Optional<JoinFacts> joins, Optional<BoundAnalysisUnit> boundUnit, Optional<String> skipReason, Optional<String> failureCode, Optional<String> failureMessage, DiagnosticBag diagnostics) {
        this(identity, statementType, rawSql, normalizedSql, status, formalLineageProduced, lineage, joins, boundUnit, skipReason,
            failureCode, failureMessage, diagnostics, Optional.<com.panfan.hivelineage.v2.frontend.ExpandedUnit>empty());
    }

    public StatementAnalysisResult(StatementIdentity identity, String statementType, SourceDocument rawSql, SourceDocument normalizedSql,
            StatementStatus status, boolean formalLineageProduced, Optional<LineageFacts> lineage, Optional<JoinFacts> joins,
            Optional<BoundAnalysisUnit> boundUnit, Optional<String> skipReason, Optional<String> failureCode, Optional<String> failureMessage,
            DiagnosticBag diagnostics, Optional<com.panfan.hivelineage.v2.frontend.ExpandedUnit> frontendEvidence) {
        this.frontendEvidence = ModelChecks.value(frontendEvidence, "frontendEvidence");
        this.identity = ModelChecks.value(identity, "identity");
        this.statementType = ModelChecks.text(statementType, "statementType");
        this.rawSql = ModelChecks.value(rawSql, "rawSql");
        this.normalizedSql = ModelChecks.value(normalizedSql, "normalizedSql");
        this.status = ModelChecks.value(status, "status");
        this.formalLineageProduced = formalLineageProduced;
        this.lineage = ModelChecks.value(lineage, "lineage");
        this.joins = ModelChecks.value(joins, "joins");
        this.boundUnit = ModelChecks.value(boundUnit, "boundUnit");
        this.skipReason = ModelChecks.value(skipReason, "skipReason");
        this.failureCode = ModelChecks.value(failureCode, "failureCode");
        this.failureMessage = ModelChecks.value(failureMessage, "failureMessage");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        if (status == StatementStatus.FAILED && (!failureCode.isPresent() || !failureMessage.isPresent())) {
            throw new IllegalArgumentException("failed statements require failure details");
        }
        if ((lineage.isPresent() || joins.isPresent()) && !boundUnit.isPresent()) {
            throw new IllegalArgumentException("analysis facts require the bound declarations used for interpretation");
        }
    }

    public Optional<com.panfan.hivelineage.v2.frontend.ExpandedUnit> getFrontendEvidence() {
        return frontendEvidence;
    }

    public StatementIdentity getIdentity() {
        return identity;
    }

    public String getStatementType() {
        return statementType;
    }

    public SourceDocument getRawSql() {
        return rawSql;
    }

    public SourceDocument getNormalizedSql() {
        return normalizedSql;
    }

    public StatementStatus getStatus() {
        return status;
    }

    public boolean isFormalLineageProduced() {
        return formalLineageProduced;
    }

    public Optional<LineageFacts> getLineage() {
        return lineage;
    }

    public Optional<JoinFacts> getJoins() {
        return joins;
    }

    public Optional<BoundAnalysisUnit> getBoundUnit() {
        return boundUnit;
    }

    public Optional<String> getSkipReason() {
        return skipReason;
    }

    public Optional<String> getFailureCode() {
        return failureCode;
    }

    public Optional<String> getFailureMessage() {
        return failureMessage;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}
