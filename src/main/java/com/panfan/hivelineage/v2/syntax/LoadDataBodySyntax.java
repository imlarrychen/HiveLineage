package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class LoadDataBodySyntax {
    private final SyntaxExpression path;
    private final boolean local;
    private final boolean overwrite;
    private final SyntaxName target;
    private final List<PartitionEntrySyntax> partitions;

    public LoadDataBodySyntax(
            SyntaxExpression path,
            boolean local,
            boolean overwrite,
            SyntaxName target,
            List<PartitionEntrySyntax> partitions) {
        this.path = Objects.requireNonNull(path, "path");
        this.local = local;
        this.overwrite = overwrite;
        this.target = Objects.requireNonNull(target, "target");
        this.partitions = ModelCollections.immutableList(partitions);
    }

    public SyntaxExpression getPath() {
        return path;
    }

    public boolean isLocal() {
        return local;
    }

    public boolean isOverwrite() {
        return overwrite;
    }

    public SyntaxName getTarget() {
        return target;
    }

    public List<PartitionEntrySyntax> getPartitions() {
        return partitions;
    }
}
