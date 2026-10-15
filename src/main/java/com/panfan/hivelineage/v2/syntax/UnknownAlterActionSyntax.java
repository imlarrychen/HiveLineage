package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

/** A parsed ALTER operation whose catalog semantics have not been modeled. */
public final class UnknownAlterActionSyntax extends AlterActionSyntax {
    private final String grammarKind;

    public UnknownAlterActionSyntax(String grammarKind) {
        this.grammarKind = Objects.requireNonNull(grammarKind, "grammarKind");
    }

    public String getGrammarKind() {
        return grammarKind;
    }
}
