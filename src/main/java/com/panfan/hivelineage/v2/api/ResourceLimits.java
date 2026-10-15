package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

/** All limits are strict upper bounds; zero permits no consumption of that resource. */
public final class ResourceLimits {
    public static final int DEFAULT_MAX_IR_DEPTH = 512;
    public static final int DEFAULT_MAX_SCRIPT_CHARACTERS = 16_000_000;
    public static final int DEFAULT_MAX_EXPANSION_DEPTH = 64;
    public static final int DEFAULT_MAX_IR_NODES = 1_000_000;
    public static final int DEFAULT_MAX_EVIDENCE_EDGES = 2_000_000;
    public static final int DEFAULT_MAX_EXTERNAL_READS = 100_000;

    public static ResourceLimits defaults() {
        return new ResourceLimits(DEFAULT_MAX_SCRIPT_CHARACTERS, DEFAULT_MAX_EXPANSION_DEPTH,
            DEFAULT_MAX_IR_NODES, DEFAULT_MAX_EVIDENCE_EDGES, DEFAULT_MAX_EXTERNAL_READS);
    }
    private final int maxScriptCharacters;
    private final int maxExpansionDepth;
    private final int maxIrNodes;
    private final int maxEvidenceEdges;
    private final int maxExternalReads;
    private final int maxIrDepth;

    public ResourceLimits(int maxScriptCharacters, int maxExpansionDepth, int maxIrNodes, int maxEvidenceEdges, int maxExternalReads) {
        this(maxScriptCharacters, maxExpansionDepth, maxIrNodes, maxEvidenceEdges, maxExternalReads, DEFAULT_MAX_IR_DEPTH);
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
