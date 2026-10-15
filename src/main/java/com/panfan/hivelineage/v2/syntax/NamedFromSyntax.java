package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class NamedFromSyntax extends FromSyntax {
    private final SyntaxName name;
    private final IdentifierPart alias;
    private final TableSampleSyntax sample;

    public NamedFromSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            SyntaxName name,
            IdentifierPart alias,
            TableSampleSyntax sample) {
        super(nodeId, sourceRef);
        this.name = Objects.requireNonNull(name, "name");
        this.alias = alias;
        this.sample = sample;
    }

    public SyntaxName getName() {
        return name;
    }

    public IdentifierPart getAlias() {
        return alias;
    }

    public TableSampleSyntax getSample() {
        return sample;
    }

    @Override
    public FromKindSyntax getKind() {
        return FromKindSyntax.NAMED;
    }
}
