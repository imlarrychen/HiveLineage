package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

public final class JoinConditionRecord {
    private final StatementIdentity statement;
    private final SourceDocument rawSql;
    private final JoinFact join;
    private final Optional<JoinPresentation> presentation;

    public JoinConditionRecord(StatementIdentity statement, SourceDocument rawSql, JoinFact join) {
        this(statement, rawSql, join, Optional.<JoinPresentation>empty());
    }

    public JoinConditionRecord(StatementIdentity statement, SourceDocument rawSql, JoinFact join, Optional<JoinPresentation> presentation) {
        this.statement = ModelChecks.value(statement, "statement");
        this.rawSql = ModelChecks.value(rawSql, "rawSql");
        this.join = ModelChecks.value(join, "join");
        this.presentation = ModelChecks.value(presentation, "presentation");
    }

    public StatementIdentity getStatement() {
        return statement;
    }

    public SourceDocument getRawSql() {
        return rawSql;
    }

    public JoinFact getJoin() {
        return join;
    }

    public Optional<JoinPresentation> getPresentation() {
        return presentation;
    }
}
