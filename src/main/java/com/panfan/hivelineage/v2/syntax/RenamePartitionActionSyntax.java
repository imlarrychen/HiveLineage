package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class RenamePartitionActionSyntax extends AlterActionSyntax {
    private final List<PartitionEntrySyntax> originalPartitions;
    private final List<PartitionEntrySyntax> newPartitions;

    public RenamePartitionActionSyntax(List<PartitionEntrySyntax> originalPartitions, List<PartitionEntrySyntax> newPartitions) {
        this.originalPartitions = ModelCollections.immutableList(originalPartitions);
        this.newPartitions = ModelCollections.immutableList(newPartitions);
    }

    public List<PartitionEntrySyntax> getOriginalPartitions() {
        return originalPartitions;
    }

    public List<PartitionEntrySyntax> getNewPartitions() {
        return newPartitions;
    }
}
