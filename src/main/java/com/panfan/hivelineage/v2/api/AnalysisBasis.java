package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.metadata.MetadataReadManifest;
import com.panfan.hivelineage.v2.session.InitialSessionContext;

public final class AnalysisBasis {
    private final DialectProfile dialect;
    private final InitialSessionContext initialSession;
    private final MetadataReadManifest metadataReads;
    private final AnalysisContract contract;

    public AnalysisBasis(DialectProfile dialect, InitialSessionContext initialSession, MetadataReadManifest metadataReads, AnalysisContract contract) {
        this.dialect = ModelChecks.value(dialect, "dialect");
        this.initialSession = ModelChecks.value(initialSession, "initialSession");
        this.metadataReads = ModelChecks.value(metadataReads, "metadataReads");
        this.contract = ModelChecks.value(contract, "contract");
    }

    public DialectProfile getDialect() {
        return dialect;
    }

    public InitialSessionContext getInitialSession() {
        return initialSession;
    }

    public MetadataReadManifest getMetadataReads() {
        return metadataReads;
    }

    public AnalysisContract getContract() {
        return contract;
    }
}
