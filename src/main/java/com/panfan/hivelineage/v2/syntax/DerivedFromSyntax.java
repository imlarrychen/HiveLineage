package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;
import java.util.Objects;

public final class DerivedFromSyntax extends FromSyntax {
    private final SyntaxQuery query;
    private final IdentifierPart alias;
    private final List<IdentifierPart> columnAliases;
    private final boolean syntheticWrapper;

    public DerivedFromSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxQuery query,
            IdentifierPart alias,
            List<IdentifierPart> columnAliases) {
        this(nodeId, sourceRef, query, alias, columnAliases, false);
    }

    public DerivedFromSyntax(NodeId nodeId, SourceRef sourceRef, SyntaxQuery query, IdentifierPart alias,
                             List<IdentifierPart> columnAliases, boolean syntheticWrapper) {
        super(nodeId, sourceRef);
        this.syntheticWrapper = syntheticWrapper;
        this.query = Objects.requireNonNull(query, "query");
        this.alias = Objects.requireNonNull(alias, "alias");
        this.columnAliases = ModelCollections.immutableList(columnAliases);
    }

    public SyntaxQuery getQuery() {
        return query;
    }

    public IdentifierPart getAlias() {
        return alias;
    }

    public List<IdentifierPart> getColumnAliases() {
        return columnAliases;
    }

    public boolean isSyntheticWrapper() {
        return syntheticWrapper;
    }

    @Override
    public FromKindSyntax getKind() {
        return FromKindSyntax.DERIVED;
    }
}
