package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class ExternalDatasetEndpoint implements LineageEndpoint {
    private final String datasetIdentity;
    private final String sourceKind;

    public ExternalDatasetEndpoint(String datasetIdentity, String sourceKind) {
        this.datasetIdentity = ModelChecks.text(datasetIdentity, "datasetIdentity");
        this.sourceKind = ModelChecks.text(sourceKind, "sourceKind");
    }

    public String getDatasetIdentity() {
        return datasetIdentity;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    @Override
    public EndpointKind getKind() {
        return EndpointKind.EXTERNAL_DATASET;
    }
}
