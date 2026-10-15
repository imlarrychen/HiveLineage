package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class DefinitionStatementSyntax extends SyntaxStatement {
    private final DefinitionBodySyntax definition;

    public DefinitionStatementSyntax(NodeId nodeId, SourceRef sourceRef, DefinitionBodySyntax definition) {
        super(nodeId, sourceRef);
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    public DefinitionBodySyntax getDefinition() {
        return definition;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.DEFINITION;
    }
}
