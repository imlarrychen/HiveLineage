package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Set;

public final class FunctionOutputDependency {
    private final int outputIndex;
    private final Set<Integer> argumentIndexes;
    private final boolean dependsOnInputRows;
    private final boolean dependsOnOrdering;

    public FunctionOutputDependency(int outputIndex, Set<Integer> argumentIndexes, boolean dependsOnInputRows, boolean dependsOnOrdering) {
        this.outputIndex = ModelChecks.nonNegative(outputIndex, "outputIndex");
        this.argumentIndexes = ModelCollections.immutableSet(argumentIndexes);
        this.dependsOnInputRows = dependsOnInputRows;
        this.dependsOnOrdering = dependsOnOrdering;
        for (Integer argumentIndex : this.argumentIndexes) {
            ModelChecks.nonNegative(argumentIndex, "argumentIndex");
        }
    }

    public int getOutputIndex() {
        return outputIndex;
    }

    public Set<Integer> getArgumentIndexes() {
        return argumentIndexes;
    }

    public boolean isDependsOnInputRows() {
        return dependsOnInputRows;
    }

    public boolean isDependsOnOrdering() {
        return dependsOnOrdering;
    }
}
