package com.panfan.hivelineage.v2.session;

public enum CommitOutcome {
    COMMITTED,
    REJECTED,
    STALE_REVISION,
    UNCERTAIN
}
