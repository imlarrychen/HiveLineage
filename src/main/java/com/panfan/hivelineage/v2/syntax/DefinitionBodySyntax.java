package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class DefinitionBodySyntax {
    private final DefinitionKindSyntax kind;
    private final SyntaxName name;
    private final DefinitionSchemaSyntax schema;
    private final DefinitionOptionsSyntax options;
    private final SyntaxQuery query;
    private final List<AlterActionSyntax> alterActions;

    public DefinitionBodySyntax(
            DefinitionKindSyntax kind,
            SyntaxName name,
            DefinitionSchemaSyntax schema,
            DefinitionOptionsSyntax options,
            SyntaxQuery query,
            List<AlterActionSyntax> alterActions) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = Objects.requireNonNull(name, "name");
        this.schema = Objects.requireNonNull(schema, "schema");
        this.options = Objects.requireNonNull(options, "options");
        this.query = query;
        this.alterActions = ModelCollections.immutableList(alterActions);
    }

    public DefinitionKindSyntax getKind() {
        return kind;
    }

    public SyntaxName getName() {
        return name;
    }

    public DefinitionSchemaSyntax getSchema() {
        return schema;
    }

    public DefinitionOptionsSyntax getOptions() {
        return options;
    }

    public SyntaxQuery getQuery() {
        return query;
    }

    public List<AlterActionSyntax> getAlterActions() {
        return alterActions;
    }
}
