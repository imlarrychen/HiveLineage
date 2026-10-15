package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.syntax.DefinitionKindSyntax;

import java.util.Collections;

/** Structural ALTER and TRUNCATE proposals do not execute catalog changes. */
final class AlterObjectEffectBinder implements CatalogEffectBinder {
    @Override
    public EffectBindingResult bind(CatalogEffectRequest request) {
        if (request.body().getKind() == DefinitionKindSyntax.TRUNCATE_TABLE) {
            BoundEffect effect = new Effects.UnknownEffect(request.id(), Effects.Certainty.UNKNOWN, request.precondition(),
                    Collections.singletonList(request.object()), Collections.<Effects.SessionKey>emptyList(), false,
                    "TRUNCATE_VERSION_UNKNOWN");
            return new EffectBindingResult(Collections.singletonList(effect), DiagnosticBag.empty());
        }
        return new EffectBindingResult(new CatalogActionBinder().alter(request), DiagnosticBag.empty());
    }
}
