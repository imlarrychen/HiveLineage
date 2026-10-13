package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;

public final class CommaFromSyntax extends FromSyntax {
    private final List<FromSyntax> sources;

    public CommaFromSyntax(NodeId nodeId, SourceRef sourceRef, List<FromSyntax> sources) {
        super(nodeId, sourceRef);
        this.sources = ModelCollections.immutableList(sources);
    }

    public List<FromSyntax> getSources() {
        return sources;
    }

    @Override
    public FromKindSyntax getKind() {
        return FromKindSyntax.COMMA;
    }
}
