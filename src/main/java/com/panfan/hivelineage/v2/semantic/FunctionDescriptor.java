package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import java.util.List;

public final class FunctionDescriptor {
    private final FunctionSymbol symbol;
    private final List<FunctionSignature> overloads;
    private final List<FunctionOutputDependency> outputDependencies;
    private final List<String> externalSourceIdentities;
    private final boolean opaqueBehavior;

    public FunctionDescriptor(FunctionSymbol symbol, List<FunctionSignature> overloads, List<FunctionOutputDependency> outputDependencies, List<String> externalSourceIdentities, boolean opaqueBehavior) {
        this.symbol = ModelChecks.value(symbol, "symbol");
        this.overloads = ModelCollections.immutableList(overloads);
        this.outputDependencies = ModelCollections.immutableList(outputDependencies);
        this.externalSourceIdentities = ModelCollections.immutableList(externalSourceIdentities);
        this.opaqueBehavior = opaqueBehavior;
    }

    public FunctionSymbol getSymbol() {
        return symbol;
    }

    public List<FunctionSignature> getOverloads() {
        return overloads;
    }

    public List<FunctionOutputDependency> getOutputDependencies() {
        return outputDependencies;
    }

    public List<String> getExternalSourceIdentities() {
        return externalSourceIdentities;
    }

    public boolean isOpaqueBehavior() {
        return opaqueBehavior;
    }
}
