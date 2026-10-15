package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;

import java.util.Collections;
import java.util.Set;

/**
 * Data describing possibly affected state; propagation decisions are not implemented here.
 */
public final class UncertaintyState {
    private final Set<ObjectId> objects;
    private final Set<VariableKey> variables;
    private final boolean catalogUncertain;
    private final boolean databaseUncertain;
    private final DiagnosticBag diagnostics;

    public UncertaintyState(Set<ObjectId> objects, Set<VariableKey> variables,
                            boolean catalogUncertain, boolean databaseUncertain,
                            DiagnosticBag diagnostics) {
        this.objects = ModelCollections.immutableSet(objects);
        this.variables = ModelCollections.immutableSet(variables);
        this.catalogUncertain = catalogUncertain;
        this.databaseUncertain = databaseUncertain;
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
    }

    public static UncertaintyState empty() {
        return new UncertaintyState(Collections.<ObjectId>emptySet(), Collections.<VariableKey>emptySet(),
                false, false, DiagnosticBag.empty());
    }

    public Set<ObjectId> getObjects() {
        return objects;
    }

    public Set<VariableKey> getVariables() {
        return variables;
    }

    public boolean isCatalogUncertain() {
        return catalogUncertain;
    }

    public boolean isDatabaseUncertain() {
        return databaseUncertain;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }
}

