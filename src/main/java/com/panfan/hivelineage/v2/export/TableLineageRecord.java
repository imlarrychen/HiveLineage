package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.List;

public final class TableLineageRecord {
    private final StatementIdentity statement;
    private final QualifiedName target;
    private final List<QualifiedName> sources;
    private final String operation;
    private final boolean throughTemporaryTable;

    public TableLineageRecord(StatementIdentity statement, QualifiedName target, List<QualifiedName> sources,
                              String operation, boolean throughTemporaryTable) {
        this.statement = ModelChecks.value(statement, "statement");
        this.target = ModelChecks.value(target, "target");
        this.sources = ModelCollections.immutableList(sources);
        this.operation = ModelChecks.text(operation, "operation");
        this.throughTemporaryTable = throughTemporaryTable;
    }

    public StatementIdentity getStatement() {
        return statement;
    }

    public QualifiedName getTarget() {
        return target;
    }

    public List<QualifiedName> getSources() {
        return sources;
    }

    public String getOperation() {
        return operation;
    }

    public boolean isThroughTemporaryTable() {
        return throughTemporaryTable;
    }
}
