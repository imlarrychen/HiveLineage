package com.panfan.hivelineage.v2.frontend;

/**
 * Variable expansion only; implementations must not mutate the JVM environment or infer lineage.
 */
public interface VariableResolver {
    ExpansionResult expand(ScriptUnit unit, VariableContext context, VariableOptions options);
}
