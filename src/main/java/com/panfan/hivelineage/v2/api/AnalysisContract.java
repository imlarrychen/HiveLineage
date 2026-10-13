package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class AnalysisContract {
    private final AnalysisCapabilities capabilities;
    private final VariablePolicy variablePolicy;
    private final DefinitionPolicy definitionPolicy;
    private final StarMappingPolicy starMappingPolicy;
    private final StatementPublicationPolicy publicationPolicy;
    private final boolean includeTemporaryLineage;
    private final boolean includeIgnoredStatements;
    private final boolean expandViewLineage;
    private final int maxViewExpandDepth;
    private final boolean failFast;
    private final String fallbackVariableValue;

    public AnalysisContract(AnalysisCapabilities capabilities, VariablePolicy variablePolicy, DefinitionPolicy definitionPolicy, StarMappingPolicy starMappingPolicy, StatementPublicationPolicy publicationPolicy, boolean includeTemporaryLineage, boolean includeIgnoredStatements, boolean expandViewLineage, int maxViewExpandDepth, boolean failFast, String fallbackVariableValue) {
        this.capabilities = ModelChecks.value(capabilities, "capabilities");
        this.variablePolicy = ModelChecks.value(variablePolicy, "variablePolicy");
        this.definitionPolicy = ModelChecks.value(definitionPolicy, "definitionPolicy");
        this.starMappingPolicy = ModelChecks.value(starMappingPolicy, "starMappingPolicy");
        this.publicationPolicy = ModelChecks.value(publicationPolicy, "publicationPolicy");
        this.includeTemporaryLineage = includeTemporaryLineage;
        this.includeIgnoredStatements = includeIgnoredStatements;
        this.expandViewLineage = expandViewLineage;
        this.maxViewExpandDepth = ModelChecks.nonNegative(maxViewExpandDepth, "maxViewExpandDepth");
        this.failFast = failFast;
        this.fallbackVariableValue = ModelChecks.value(fallbackVariableValue, "fallbackVariableValue");
    }

    public AnalysisCapabilities getCapabilities() {
        return capabilities;
    }

    public VariablePolicy getVariablePolicy() {
        return variablePolicy;
    }

    public DefinitionPolicy getDefinitionPolicy() {
        return definitionPolicy;
    }

    public StarMappingPolicy getStarMappingPolicy() {
        return starMappingPolicy;
    }

    public StatementPublicationPolicy getPublicationPolicy() {
        return publicationPolicy;
    }

    public boolean isIncludeTemporaryLineage() {
        return includeTemporaryLineage;
    }

    public boolean isIncludeIgnoredStatements() {
        return includeIgnoredStatements;
    }

    public boolean isExpandViewLineage() {
        return expandViewLineage;
    }

    public int getMaxViewExpandDepth() {
        return maxViewExpandDepth;
    }

    public boolean isFailFast() {
        return failFast;
    }

    public String getFallbackVariableValue() {
        return fallbackVariableValue;
    }
}
