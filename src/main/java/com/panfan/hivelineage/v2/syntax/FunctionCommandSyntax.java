package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class FunctionCommandSyntax extends CommandSyntax {
    private final SyntaxName name;
    private final String className;
    private final boolean temporary;
    private final boolean drop;
    private final List<FunctionResourceSyntax> resources;

    public FunctionCommandSyntax(
            SyntaxName name,
            String className,
            boolean temporary,
            boolean drop,
            List<FunctionResourceSyntax> resources) {
        super();
        this.name = Objects.requireNonNull(name, "name");
        this.className = className;
        this.temporary = temporary;
        this.drop = drop;
        this.resources = ModelCollections.immutableList(resources);
    }

    public SyntaxName getName() {
        return name;
    }

    public String getClassName() {
        return className;
    }

    public boolean isTemporary() {
        return temporary;
    }

    public boolean isDrop() {
        return drop;
    }

    public List<FunctionResourceSyntax> getResources() {
        return resources;
    }
}
