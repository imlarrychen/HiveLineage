package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;

import java.util.List;

/**
 * Static data-version record, separate from claims about actual execution.
 */
public final class DataVersionState {
    public enum Basis {
        SCRIPT_ENTRY,
        ASSUMED_PREDECESSOR_SUCCESS,
        UNKNOWN
    }

    private final VersionRef version;
    private final List<DatasetVersionId> predecessors;
    private final Basis basis;

    public DataVersionState(VersionRef version, List<DatasetVersionId> predecessors, Basis basis) {
        this.version = ModelChecks.value(version, "version");
        this.predecessors = ModelCollections.immutableList(predecessors);
        this.basis = ModelChecks.value(basis, "basis");
        if (version.getState() == VersionRef.State.NOT_REQUESTED && !predecessors.isEmpty()) {
            throw new IllegalArgumentException("unrequested versions cannot declare predecessors");
        }
    }

    public VersionRef getVersion() {
        return version;
    }

    public List<DatasetVersionId> getPredecessors() {
        return predecessors;
    }

    public Basis getBasis() {
        return basis;
    }
}

