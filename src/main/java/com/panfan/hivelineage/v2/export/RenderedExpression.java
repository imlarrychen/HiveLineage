package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.common.ModelChecks;

import java.util.Optional;

public final class RenderedExpression {
    private final Optional<String> text;
    private final boolean extracted;

    public RenderedExpression(Optional<String> text, boolean extracted) {
        this.text = ModelChecks.value(text, "text");
        this.extracted = extracted;
        if (extracted && !text.isPresent()) {
            throw new IllegalArgumentException("extracted expression requires text");
        }
    }

    public Optional<String> getText() {
        return text;
    }

    public boolean isExtracted() {
        return extracted;
    }
}
