package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class LineageDifferenceReport {
    private final String corpusId;
    private final List<Difference> differences;
    private final int totalCases;
    private final int unsupportedCases;
    private final int pendingCases;

    public LineageDifferenceReport(String corpusId, List<Difference> differences, int totalCases, int unsupportedCases, int pendingCases) {
        this.corpusId = ModelChecks.text(corpusId, "corpusId");
        this.differences = ModelCollections.immutableList(differences);
        this.totalCases = ModelChecks.nonNegative(totalCases, "totalCases");
        this.unsupportedCases = ModelChecks.nonNegative(unsupportedCases, "unsupportedCases");
        this.pendingCases = ModelChecks.nonNegative(pendingCases, "pendingCases");
    }

    public String getCorpusId() {
        return corpusId;
    }

    public List<Difference> getDifferences() {
        return differences;
    }

    public int getTotalCases() {
        return totalCases;
    }

    public int getUnsupportedCases() {
        return unsupportedCases;
    }

    public int getPendingCases() {
        return pendingCases;
    }
}
