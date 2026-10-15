package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;

import java.util.Collections;

/** DROP proposals retain canonical identities and explicit existence preconditions. */
final class DropObjectEffectBinder implements CatalogEffectBinder {
    @Override
    public EffectBindingResult bind(CatalogEffectRequest request) {
        BoundEffect effect = new Effects.DropObject(request.id(), Effects.Certainty.CERTAIN,
                request.precondition(), request.object(), request.kind(), request.name());
        return new EffectBindingResult(Collections.singletonList(effect), DiagnosticBag.empty());
    }
}
