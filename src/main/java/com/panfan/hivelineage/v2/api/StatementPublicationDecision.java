package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.analysis.JoinFacts;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.syntax.OtherStatementSyntax;
import com.panfan.hivelineage.v2.syntax.QueryStatementSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

import java.util.Optional;

/**
 * One policy decision shared by binding, analysis and result publication.
 */
final class StatementPublicationDecision {
    private final SyntaxStatement syntax;
    private final AnalysisContract contract;
    private final StatementClassification classification;

    StatementPublicationDecision(SyntaxStatement syntax, AnalysisContract contract, StatementClassification classification) {
        this.syntax = syntax;
        this.contract = contract;
        this.classification = classification;
    }

    boolean ignoreBeforeBinding() {
        if (syntax instanceof OtherStatementSyntax) {
            return !classification.formalCandidate;
        }
        return syntax instanceof QueryStatementSyntax
                && ((QueryStatementSyntax) syntax).getQuery().getCtes().isEmpty()
                && !contract.getCapabilities().supports(AnalysisCapability.JOIN_CONDITION)
                && contract.getPublicationPolicy() == StatementPublicationPolicy.CURRENT_API;
    }

    boolean formal(BoundAnalysisUnit unit) {
        return classification.formalCandidate && !unit.getWrites().isEmpty() && hasLineageCapability();
    }

    boolean analyzeLineage(BoundAnalysisUnit unit) {
        return hasLineageCapability() && (formal(unit) || detailedQuery());
    }

    boolean published(boolean formal, Optional<LineageFacts> lineage, Optional<JoinFacts> joins) {
        return formal || detailedQuery() && lineage.isPresent() || joins.isPresent() && !joins.get().getJoins().isEmpty();
    }

    private boolean detailedQuery() {
        return syntax instanceof QueryStatementSyntax && contract.getPublicationPolicy() == StatementPublicationPolicy.DETAILED;
    }

    private boolean hasLineageCapability() {
        return contract.getCapabilities().supports(AnalysisCapability.TABLE_LINEAGE)
                || contract.getCapabilities().supports(AnalysisCapability.COLUMN_LINEAGE)
                || contract.getCapabilities().supports(AnalysisCapability.EXTERNAL_DATA_LINEAGE);
    }
}
