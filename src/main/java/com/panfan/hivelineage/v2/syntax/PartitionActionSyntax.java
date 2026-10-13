package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class PartitionActionSyntax extends AlterActionSyntax {
    private final PartitionChangeKindSyntax kind;
    private final List<PartitionEntrySyntax> partitions;
    private final SyntaxExpression location;

    public PartitionActionSyntax(PartitionChangeKindSyntax kind, List<PartitionEntrySyntax> partitions, SyntaxExpression location) {
        super();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.partitions = ModelCollections.immutableList(partitions);
        this.location = location;
    }

    public PartitionChangeKindSyntax getKind() {
        return kind;
    }

    public List<PartitionEntrySyntax> getPartitions() {
        return partitions;
    }

    public SyntaxExpression getLocation() {
        return location;
    }
}
