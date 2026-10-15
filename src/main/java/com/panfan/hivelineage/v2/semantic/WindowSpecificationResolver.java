package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.syntax.NamedWindowSyntax;
import com.panfan.hivelineage.v2.syntax.WindowSpecificationSyntax;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Expands named-window inheritance before either field or object dependency binding.
 */
final class WindowSpecificationResolver {
    private WindowSpecificationResolver() {
    }

    static WindowSpecificationSyntax resolve(WindowSpecificationSyntax window, ReferenceScope scope,
                                             BindingWork work, SourceRef source) {
        return resolve(window, scope, work, source, new LinkedHashSet<String>());
    }

    private static WindowSpecificationSyntax resolve(WindowSpecificationSyntax window, ReferenceScope scope,
                                                     BindingWork work, SourceRef source, Set<String> active) {
        WindowSpecificationSyntax base = null;
        if (window.getBaseWindowName() != null) {
            String name = SemanticNames.normalize(window.getBaseWindowName().getValue());
            NamedWindowSyntax named = scope.windows.get(name);
            if (named == null) {
                work.warn("UNKNOWN_WINDOW", "Window definition is unavailable: " + name, source);
            } else if (!active.add(name)) {
                work.warn("RECURSIVE_WINDOW_DEFINITION", "Named windows form an inheritance cycle: " + name, source);
            } else {
                base = resolve(named.getSpecification(), scope, work, source, active);
                active.remove(name);
            }
        }
        return new WindowSpecificationSyntax(null,
                window.getPartitionBy().isEmpty() && base != null ? base.getPartitionBy() : window.getPartitionBy(),
                window.getOrderBy().isEmpty() && base != null ? base.getOrderBy() : window.getOrderBy(),
                window.getFrame() == null && base != null ? base.getFrame() : window.getFrame());
    }
}
