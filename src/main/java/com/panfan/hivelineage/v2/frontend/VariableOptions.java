package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

public final class VariableOptions {
    private final VariableResolutionPolicy policy;
    private final String fallbackValue;
    private final int maximumExpandedLength;
    private final Set<String> builtinFallbackVariables;

    public VariableOptions(VariableResolutionPolicy policy, String fallbackValue, int maximumExpandedLength) {
        this(policy, fallbackValue, maximumExpandedLength, Collections.singleton("bizdate"));
    }

    public VariableOptions(VariableResolutionPolicy policy, String fallbackValue, int maximumExpandedLength,
                           Set<String> builtinFallbackVariables) {
        if (maximumExpandedLength < 0) {
            throw new IllegalArgumentException("maximumExpandedLength must be non-negative");
        }
        this.policy = Objects.requireNonNull(policy, "policy");
        this.fallbackValue = fallbackValue;
        this.maximumExpandedLength = maximumExpandedLength;
        this.builtinFallbackVariables = ModelCollections.immutableSet(builtinFallbackVariables);
        for (String name : this.builtinFallbackVariables) {
            ModelChecks.text(name, "builtinFallbackVariable");
        }
    }

    public VariableResolutionPolicy getPolicy() {
        return policy;
    }

    public String getFallbackValue() {
        return fallbackValue;
    }

    public int getMaximumExpandedLength() {
        return maximumExpandedLength;
    }

    public Set<String> getBuiltinFallbackVariables() {
        return builtinFallbackVariables;
    }
}
