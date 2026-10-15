package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.ir.statement.BoundEffect;

/**
 * Evaluates preconditions once, dispatches handlers, and atomically publishes the delta.
 */
public final class DefaultSessionEffectCommitter implements SessionEffectCommitter {
    @Override
    public SessionCommitResult commit(SessionSnapshot before, SessionEffectPlan plan) {
        if (before.getRevision() != plan.getExpectedRevision()) {
            return new SessionCommitResult(CommitOutcome.STALE_REVISION, before, plan.getDiagnostics());
        }
        SessionStateDelta delta = new SessionStateDelta(before, plan);
        for (BoundEffect effect : plan.getEffects()) {
            switch (EffectPreconditionEvaluator.decide(delta, effect)) {
                case UNCERTAIN:
                    delta.markUncertain(effect);
                    break;
                case SKIP:
                    delta.suppressCreateVersion(effect);
                    break;
                case APPLY:
                    SessionEffectHandlers.apply(delta, effect);
                    break;
                default:
                    throw new IllegalStateException("Unrecognized effect decision");
            }
        }
        return delta.commit();
    }
}
