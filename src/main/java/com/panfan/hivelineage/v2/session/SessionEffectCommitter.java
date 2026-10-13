package com.panfan.hivelineage.v2.session;

/** Declares verified-plan commit ownership; this phase provides no apply implementation. */
public interface SessionEffectCommitter {
    SessionCommitResult commit(SessionSnapshot before, SessionEffectPlan plan);
}
