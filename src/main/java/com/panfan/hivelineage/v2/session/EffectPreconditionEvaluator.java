package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;

/** Preconditions and uncertainty have one ordering, independent of effect handlers. */
final class EffectPreconditionEvaluator {
    enum Decision { APPLY, SKIP, UNCERTAIN }

    private EffectPreconditionEvaluator() { }

    static Decision decide(SessionStateDelta state, BoundEffect effect) {
        if (effect.getCertainty() == Effects.Certainty.UNKNOWN || effect instanceof Effects.UnknownEffect
            || effect.getPrecondition().getKind() == Effects.Precondition.Kind.UNKNOWN) {
            return Decision.UNCERTAIN;
        }
        if (state.creationBranchApplied(effect)) {
            return Decision.APPLY;
        }
        if (effect.getCertainty() == Effects.Certainty.CONDITIONAL && !state.preconditionKnown(effect)) {
            return Decision.UNCERTAIN;
        }
        boolean namedDrop = effect instanceof Effects.DropObject && ((Effects.DropObject) effect).getName().isPresent()
            && effect.getPrecondition().getKind() == Effects.Precondition.Kind.OBJECT_PRESENT;
        if (namedDrop || state.preconditionSatisfied(effect.getPrecondition())) {
            return Decision.APPLY;
        }
        return Decision.SKIP;
    }
}
