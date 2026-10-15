package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.session.DataVersionState;

import java.util.Optional;

/**
 * Static committed state after one statement; it does not assert successful execution.
 */
public final class DatasetVersionRecord {
    private final StatementIdentity statement;
    private final ObjectId objectId;
    private final Optional<QualifiedName> displayName;
    private final DataVersionState state;
    private final BoundAnalysisUnit.ExecutionAssumption assumption;

    public DatasetVersionRecord(StatementIdentity statement, ObjectId objectId, Optional<QualifiedName> displayName,
                                DataVersionState state, BoundAnalysisUnit.ExecutionAssumption assumption) {
        this.statement = ModelChecks.value(statement, "statement");
        this.objectId = ModelChecks.value(objectId, "objectId");
        this.displayName = ModelChecks.value(displayName, "displayName");
        this.state = ModelChecks.value(state, "state");
        this.assumption = ModelChecks.value(assumption, "assumption");
    }

    public StatementIdentity getStatement() {
        return statement;
    }

    public ObjectId getObjectId() {
        return objectId;
    }

    public Optional<QualifiedName> getDisplayName() {
        return displayName;
    }

    public DataVersionState getState() {
        return state;
    }

    public BoundAnalysisUnit.ExecutionAssumption getAssumption() {
        return assumption;
    }
}
