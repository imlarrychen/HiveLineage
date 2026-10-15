package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class EngineIdentity {
    public static final String V2_ENGINE_ID = "hive-lineage-v2";
    public static final String V2_ENGINE_VERSION = "2.0.0";

    public static EngineIdentity v2(String rulesVersion) {
        return new EngineIdentity(V2_ENGINE_ID, V2_ENGINE_VERSION, rulesVersion);
    }

    private final String engineId;
    private final String engineVersion;
    private final String rulesVersion;

    public EngineIdentity(String engineId, String engineVersion, String rulesVersion) {
        this.engineId = ModelChecks.text(engineId, "engineId");
        this.engineVersion = ModelChecks.text(engineVersion, "engineVersion");
        this.rulesVersion = ModelChecks.text(rulesVersion, "rulesVersion");
    }

    public String getEngineId() {
        return engineId;
    }

    public String getEngineVersion() {
        return engineVersion;
    }

    public String getRulesVersion() {
        return rulesVersion;
    }
}
