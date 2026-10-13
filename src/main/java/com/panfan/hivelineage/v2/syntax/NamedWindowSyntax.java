package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class NamedWindowSyntax {
    private final IdentifierPart name;
    private final WindowSpecificationSyntax specification;

    public NamedWindowSyntax(IdentifierPart name, WindowSpecificationSyntax specification) {
        this.name = Objects.requireNonNull(name, "name");
        this.specification = Objects.requireNonNull(specification, "specification");
    }

    public IdentifierPart getName() {
        return name;
    }

    public WindowSpecificationSyntax getSpecification() {
        return specification;
    }
}
