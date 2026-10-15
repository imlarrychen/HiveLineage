package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.List;
import java.util.Objects;

/**
 * Statement-scoped syntax; no cross-statement or physical source mapping.
 */
public final class CteSyntax {
    private final NodeId nodeId;
    private final SourceRef sourceRef;
    private final IdentifierPart name;
    private final List<IdentifierPart> columnAliases;
    private final SyntaxQuery query;

    public CteSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            IdentifierPart name,
            List<IdentifierPart> columnAliases,
            SyntaxQuery query) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sourceRef = Objects.requireNonNull(sourceRef, "sourceRef");
        this.name = Objects.requireNonNull(name, "name");
        this.columnAliases = ModelCollections.immutableList(columnAliases);
        this.query = Objects.requireNonNull(query, "query");
    }

    public NodeId getNodeId() {
        return nodeId;
    }

    public SourceRef getSourceRef() {
        return sourceRef;
    }

    public IdentifierPart getName() {
        return name;
    }

    public List<IdentifierPart> getColumnAliases() {
        return columnAliases;
    }

    public SyntaxQuery getQuery() {
        return query;
    }
}
