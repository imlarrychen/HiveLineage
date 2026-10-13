package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;

public final class LineageRecord {
    private final StatementIdentity statement;
    private final Dependency dependency;

    public LineageRecord(StatementIdentity statement, Dependency dependency) {
        this.statement = ModelChecks.value(statement, "statement");
        this.dependency = ModelChecks.value(dependency, "dependency");
    }

    public StatementIdentity getStatement() {
        return statement;
    }

    public Dependency getDependency() {
        return dependency;
    }
}
