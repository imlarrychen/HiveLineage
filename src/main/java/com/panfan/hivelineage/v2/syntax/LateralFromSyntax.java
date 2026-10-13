package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.List;
import java.util.Objects;

public final class LateralFromSyntax extends FromSyntax {
    private final FromSyntax input;
    private final List<GenerateSyntax> generators;

    public LateralFromSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            FromSyntax input,
            List<GenerateSyntax> generators) {
        super(nodeId, sourceRef);
        this.input = Objects.requireNonNull(input, "input");
        this.generators = ModelCollections.immutableList(generators);
    }

    public FromSyntax getInput() {
        return input;
    }

    public List<GenerateSyntax> getGenerators() {
        return generators;
    }

    @Override
    public FromKindSyntax getKind() {
        return FromKindSyntax.LATERAL;
    }
}
