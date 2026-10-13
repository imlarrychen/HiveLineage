package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class UseCommandSyntax extends CommandSyntax {
    private final SyntaxName database;

    public UseCommandSyntax(SyntaxName database) {
        super();
        this.database = Objects.requireNonNull(database, "database");
    }

    public SyntaxName getDatabase() {
        return database;
    }
}
