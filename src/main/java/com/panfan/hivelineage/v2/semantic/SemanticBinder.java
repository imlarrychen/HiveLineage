package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

/**
 * Only binds names and semantic structure. Does not parse SQL or generate lineage.
 */
public interface SemanticBinder {
    BindingResult bind(SyntaxStatement syntax, BindingContext context, BindingDemand demand);
}
