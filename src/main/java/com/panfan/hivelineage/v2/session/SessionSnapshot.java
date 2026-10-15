package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable per-script state. No method resolves names or commits effects.
 */
public final class SessionSnapshot {
    private final InitialSessionContext initialContext;
    private final Optional<QualifiedName> currentDatabase;
    private final Map<VariableKey, VariableState> variables;
    private final Map<QualifiedName, CatalogEntry> temporaryCatalog;
    private final Map<QualifiedName, OverlayEntry> persistentOverlay;
    private final Map<QualifiedName, LogicalDefinition> scriptDefinitions;
    private final Map<ObjectId, DataVersionState> datasetVersions;
    private final UncertaintyState uncertainty;
    private final long revision;

    public SessionSnapshot(InitialSessionContext initialContext, Optional<QualifiedName> currentDatabase,
                           Map<VariableKey, VariableState> variables,
                           Map<QualifiedName, CatalogEntry> temporaryCatalog,
                           Map<QualifiedName, OverlayEntry> persistentOverlay,
                           Map<QualifiedName, LogicalDefinition> scriptDefinitions,
                           Map<ObjectId, DataVersionState> datasetVersions,
                           UncertaintyState uncertainty, long revision) {
        this.initialContext = ModelChecks.value(initialContext, "initialContext");
        this.currentDatabase = ModelChecks.value(currentDatabase, "currentDatabase");
        this.variables = ModelCollections.immutableMap(variables);
        this.temporaryCatalog = ModelCollections.immutableMap(temporaryCatalog);
        this.persistentOverlay = ModelCollections.immutableMap(persistentOverlay);
        this.scriptDefinitions = ModelCollections.immutableMap(scriptDefinitions);
        this.datasetVersions = ModelCollections.immutableMap(datasetVersions);
        this.uncertainty = ModelChecks.value(uncertainty, "uncertainty");
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
    }

    public static SessionSnapshot initial(InitialSessionContext context) {
        ModelChecks.value(context, "context");
        return new SessionSnapshot(context, context.getDefaultDatabase(), context.getInitialVariables(),
                Collections.<QualifiedName, CatalogEntry>emptyMap(),
                Collections.<QualifiedName, OverlayEntry>emptyMap(),
                Collections.<QualifiedName, LogicalDefinition>emptyMap(),
                Collections.<ObjectId, DataVersionState>emptyMap(), UncertaintyState.empty(), 0L);
    }

    public InitialSessionContext getInitialContext() {
        return initialContext;
    }

    public Optional<QualifiedName> getCurrentDatabase() {
        return currentDatabase;
    }

    public Map<VariableKey, VariableState> getVariables() {
        return variables;
    }

    public Map<QualifiedName, CatalogEntry> getTemporaryCatalog() {
        return temporaryCatalog;
    }

    public Map<QualifiedName, OverlayEntry> getPersistentOverlay() {
        return persistentOverlay;
    }

    public Map<QualifiedName, LogicalDefinition> getScriptDefinitions() {
        return scriptDefinitions;
    }

    public Map<ObjectId, DataVersionState> getDatasetVersions() {
        return datasetVersions;
    }

    public UncertaintyState getUncertainty() {
        return uncertainty;
    }

    public long getRevision() {
        return revision;
    }
}

