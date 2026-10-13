package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class SourceCommandSyntax extends CommandSyntax {
    private final String path;

    public SourceCommandSyntax(String path) {
        super();
        this.path = Objects.requireNonNull(path, "path");
    }

    public String getPath() {
        return path;
    }
}
