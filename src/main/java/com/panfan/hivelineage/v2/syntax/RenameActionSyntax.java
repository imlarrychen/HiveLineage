package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class RenameActionSyntax extends AlterActionSyntax {
    private final SyntaxName newName;

    public RenameActionSyntax(SyntaxName newName) {
        super();
        this.newName = Objects.requireNonNull(newName, "newName");
    }

    public SyntaxName getNewName() {
        return newName;
    }
}
