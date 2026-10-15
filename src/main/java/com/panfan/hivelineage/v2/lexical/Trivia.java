package com.panfan.hivelineage.v2.lexical;

import com.panfan.hivelineage.v2.common.SourceRef;

import java.util.Objects;

public final class Trivia {
    private final TriviaKind kind;
    private final String text;
    private final SourceRef sourceRef;

    public Trivia(TriviaKind kind, String text, SourceRef sourceRef) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.text = Objects.requireNonNull(text, "text");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
    }

    public TriviaKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }
}
