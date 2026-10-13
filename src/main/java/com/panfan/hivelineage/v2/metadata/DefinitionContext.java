package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.QualifiedName;
import java.util.Map;
import java.util.Optional;

/** Immutable creation/binding context for a definition, without task-local callbacks. */
public final class DefinitionContext {
    private final Optional<QualifiedName> defaultDatabase;
    private final Map<String, String> variables;
    private final String dialectId;
    private final String parserVersion;
    private final String ruleVersion;
    private final MetadataReadContext readContext;

    public DefinitionContext(Optional<QualifiedName> defaultDatabase, Map<String, String> variables,
                             String dialectId, String parserVersion, String ruleVersion,
                             MetadataReadContext readContext) {
        this.defaultDatabase = ModelChecks.value(defaultDatabase, "defaultDatabase");
        this.variables = ModelCollections.immutableMap(variables);
        this.dialectId = ModelChecks.text(dialectId, "dialectId");
        this.parserVersion = ModelChecks.text(parserVersion, "parserVersion");
        this.ruleVersion = ModelChecks.text(ruleVersion, "ruleVersion");
        this.readContext = ModelChecks.value(readContext, "readContext");
    }

    public Optional<QualifiedName> getDefaultDatabase() {
        return defaultDatabase;
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public String getDialectId() {
        return dialectId;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public MetadataReadContext getReadContext() {
        return readContext;
    }
}

