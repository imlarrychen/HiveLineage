package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.ModelChecks;

public final class SourceDocument {
    private final String sourceId;
    private final String text;

    public SourceDocument(String sourceId, String text) {
        this.sourceId = ModelChecks.text(sourceId, "sourceId");
        this.text = ModelChecks.value(text, "text");
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getText() {
        return text;
    }
}
