package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

/** Expanded SQL text plus evidence; no tokens, AST, metadata or lineage facts. */
public final class ExpandedUnit {
    private final ScriptUnit originalUnit;
    private final String expandedSourceId;
    private final String expandedText;
    private final SourceMap sourceMap;
    private final List<VariableEvidence> variableEvidence;

    public ExpandedUnit(
            ScriptUnit originalUnit,
            String expandedSourceId,
            String expandedText,
            SourceMap sourceMap,
            List<VariableEvidence> variableEvidence) {
        this.originalUnit = Objects.requireNonNull(originalUnit, "originalUnit");
        this.expandedSourceId = Objects.requireNonNull(expandedSourceId, "expandedSourceId");
        this.expandedText = Objects.requireNonNull(expandedText, "expandedText");
        this.sourceMap = Objects.requireNonNull(sourceMap, "sourceMap");
        this.variableEvidence = ModelCollections.immutableList(variableEvidence);
    }

    public ScriptUnit getOriginalUnit() {
        return originalUnit;
    }

    public String getExpandedSourceId() {
        return expandedSourceId;
    }

    public String getExpandedText() {
        return expandedText;
    }

    public SourceMap getSourceMap() {
        return sourceMap;
    }

    public List<VariableEvidence> getVariableEvidence() {
        return variableEvidence;
    }
}
