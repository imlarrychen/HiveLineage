package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

public interface EffectBinder {
    EffectBindingResult bind(SyntaxStatement statement, BindingContext context, BindingDemand demand);
}
