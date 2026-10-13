package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

/** All limits are strict upper bounds; zero permits no consumption of that resource. */
public final class ResourceLimits {
    private final int maxScriptCharacters;
    private final int maxExpansionDepth;
    private final int maxIrNodes;
    private final int maxEvidenceEdges;
    private final int maxExternalReads;
    private final int maxIrDepth;

    public ResourceLimits(int maxScriptCharacters, int maxExpansionDepth, int maxIrNodes, int maxEvidenceEdges, int maxExternalReads) {
        this(maxScriptCharacters, maxExpansionDepth, maxIrNodes, maxEvidenceEdges, maxExternalReads, 512);
    }

    public ResourceLimits(int maxScriptCharacters, int maxExpansionDepth, int maxIrNodes, int maxEvidenceEdges, int maxExternalReads, int maxIrDepth) {
        this.maxScriptCharacters = ModelChecks.nonNegative(maxScriptCharacters, "maxScriptCharacters");
        this.maxExpansionDepth = ModelChecks.nonNegative(maxExpansionDepth, "maxExpansionDepth");
        this.maxIrNodes = ModelChecks.nonNegative(maxIrNodes, "maxIrNodes");
        this.maxEvidenceEdges = ModelChecks.nonNegative(maxEvidenceEdges, "maxEvidenceEdges");
        this.maxExternalReads = ModelChecks.nonNegative(maxExternalReads, "maxExternalReads");
        this.maxIrDepth = ModelChecks.nonNegative(maxIrDepth, "maxIrDepth");
    }

    public int getMaxIrDepth() {
        return maxIrDepth;
    }

    public int getMaxScriptCharacters() {
        return maxScriptCharacters;
    }

    public int getMaxExpansionDepth() {
        return maxExpansionDepth;
    }

    public int getMaxIrNodes() {
        return maxIrNodes;
    }

    public int getMaxEvidenceEdges() {
        return maxEvidenceEdges;
    }

    public int getMaxExternalReads() {
        return maxExternalReads;
    }
}
