package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.identity.NodeId;

/** A proposed immutable effect; only orchestration may commit it to a Session. */
public interface BoundEffect {
    NodeId getId();
    Effects.EffectKind getKind();
    Effects.Certainty getCertainty();
    Effects.Precondition getPrecondition();
}
