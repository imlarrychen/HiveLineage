package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class EngineIdentity {
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
