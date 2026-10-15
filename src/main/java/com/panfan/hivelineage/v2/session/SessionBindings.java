package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.statement.Effects;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Transaction-local database and variable values; uncertain values cannot retain a guessed value. */
final class SessionBindings {
    private Optional<QualifiedName> database;
    private final Map<VariableKey, VariableState> variables;

    SessionBindings(SessionSnapshot before) {
        database = before.getCurrentDatabase();
        variables = new LinkedHashMap<>(before.getVariables());
    }

    void setDatabase(QualifiedName name) {
        database = Optional.of(name);
    }

    VariableKey setVariable(Effects.SessionKey key, String value) {
        VariableKey variable = SessionCatalogConversions.variableKey(key);
        variables.put(variable, new VariableState(VariableState.Resolution.KNOWN, Optional.of(value), SourceRef.synthetic("session")));
        return variable;
    }

    VariableKey taint(Effects.SessionKey key) {
        VariableKey variable = SessionCatalogConversions.variableKey(key);
        variables.put(variable, new VariableState(VariableState.Resolution.UNRESOLVED, Optional.<String>empty(), SourceRef.synthetic("session")));
        return variable;
    }

    Optional<QualifiedName> database() {
        return database;
    }

    Map<VariableKey, VariableState> variables() {
        return Collections.unmodifiableMap(variables);
    }
}
