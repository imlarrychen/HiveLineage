package com.panfan.hivelineage.v2.dialect;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.Objects;
import java.util.Set;

public final class DialectProfile {
    private final String dialectId;
    private final String parserVersion;
    private final String semanticRuleVersion;
    private final Set<String> features;

    public DialectProfile(
            String dialectId,
            String parserVersion,
            String semanticRuleVersion,
            Set<String> features) {
        this.dialectId = Objects.requireNonNull(dialectId, "dialectId");
        this.parserVersion = Objects.requireNonNull(parserVersion, "parserVersion");
        this.semanticRuleVersion = Objects.requireNonNull(semanticRuleVersion, "semanticRuleVersion");
        this.features = ModelCollections.immutableSet(features);
    }

    public String getDialectId() {
        return dialectId;
    }

    public String getParserVersion() {
        return parserVersion;
    }

    public String getSemanticRuleVersion() {
        return semanticRuleVersion;
    }

    public Set<String> getFeatures() {
        return features;
    }
}
