package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.metadata.MetadataAccess;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.session.SessionSnapshot;

import java.util.Map;
import java.util.Collections;

public final class BindingContext {
    private final SessionSnapshot session;
    private final MetadataAccess metadata;
    private final MetadataReadContext metadataContext;
    private final Map<DefinitionId, PreparedDefinition> definitions;
    private final FunctionCatalog functions;
    private final DialectProfile dialect;
    private final AnalysisContract contract;
    private final Map<DefinitionId, DiagnosticBag> failedDefinitions;

    public BindingContext(SessionSnapshot session, MetadataAccess metadata, MetadataReadContext metadataContext, Map<DefinitionId, PreparedDefinition> definitions, FunctionCatalog functions, DialectProfile dialect, AnalysisContract contract) {
        this(session, metadata, metadataContext, definitions, functions, dialect, contract,
                Collections.<DefinitionId, DiagnosticBag>emptyMap());
    }

    public BindingContext(SessionSnapshot session, MetadataAccess metadata, MetadataReadContext metadataContext, Map<DefinitionId, PreparedDefinition> definitions, FunctionCatalog functions, DialectProfile dialect, AnalysisContract contract, Map<DefinitionId, DiagnosticBag> failedDefinitions) {
        this.failedDefinitions = ModelCollections.immutableMap(failedDefinitions);
        this.session = ModelChecks.value(session, "session");
        this.metadata = ModelChecks.value(metadata, "metadata");
        this.metadataContext = ModelChecks.value(metadataContext, "metadataContext");
        this.definitions = ModelCollections.immutableMap(definitions);
        this.functions = ModelChecks.value(functions, "functions");
        this.dialect = ModelChecks.value(dialect, "dialect");
        this.contract = ModelChecks.value(contract, "contract");
    }

    public Map<DefinitionId, DiagnosticBag> getFailedDefinitions() {
        return failedDefinitions;
    }

    public SessionSnapshot getSession() {
        return session;
    }

    public MetadataAccess getMetadata() {
        return metadata;
    }

    public MetadataReadContext getMetadataContext() {
        return metadataContext;
    }

    public Map<DefinitionId, PreparedDefinition> getDefinitions() {
        return definitions;
    }

    public FunctionCatalog getFunctions() {
        return functions;
    }

    public DialectProfile getDialect() {
        return dialect;
    }

    public AnalysisContract getContract() {
        return contract;
    }

}
