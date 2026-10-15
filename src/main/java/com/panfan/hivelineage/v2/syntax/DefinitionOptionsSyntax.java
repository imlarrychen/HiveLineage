package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.Map;

public final class DefinitionOptionsSyntax {
    private final boolean temporary;
    private final boolean external;
    private final boolean ifNotExists;
    private final boolean ifExists;
    private final Map<String, String> properties;
    private final SyntaxExpression location;
    private final boolean orReplace;
    private final String comment;

    public DefinitionOptionsSyntax(
            boolean temporary,
            boolean external,
            boolean ifNotExists,
            boolean ifExists,
            Map<String, String> properties,
            SyntaxExpression location) {
        this(temporary, external, ifNotExists, ifExists, properties, location, false, null);
    }

    public DefinitionOptionsSyntax(
            boolean temporary,
            boolean external,
            boolean ifNotExists,
            boolean ifExists,
            Map<String, String> properties,
            SyntaxExpression location,
            boolean orReplace,
            String comment) {
        this.temporary = temporary;
        this.external = external;
        this.ifNotExists = ifNotExists;
        this.ifExists = ifExists;
        this.properties = ModelCollections.immutableMap(properties);
        this.location = location;
        this.orReplace = orReplace;
        this.comment = comment;
    }

    public boolean isTemporary() {
        return temporary;
    }

    public boolean isExternal() {
        return external;
    }

    public boolean isIfNotExists() {
        return ifNotExists;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public SyntaxExpression getLocation() {
        return location;
    }

    public boolean isOrReplace() {
        return orReplace;
    }

    public boolean getOrReplace() {
        return orReplace;
    }

    public String getComment() {
        return comment;
    }
}
