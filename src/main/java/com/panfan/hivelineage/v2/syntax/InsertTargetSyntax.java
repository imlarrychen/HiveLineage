package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class InsertTargetSyntax {
    private final InsertTargetKindSyntax kind;
    private final SyntaxName tableName;
    private final SyntaxExpression directory;
    private final List<IdentifierPart> targetColumns;
    private final List<PartitionEntrySyntax> partitions;

    public InsertTargetSyntax(
            InsertTargetKindSyntax kind,
            SyntaxName tableName,
            SyntaxExpression directory,
            List<IdentifierPart> targetColumns,
            List<PartitionEntrySyntax> partitions) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.tableName = tableName;
        this.directory = directory;
        this.targetColumns = ModelCollections.immutableList(targetColumns);
        this.partitions = ModelCollections.immutableList(partitions);
    }

    public InsertTargetKindSyntax getKind() {
        return kind;
    }

    public SyntaxName getTableName() {
        return tableName;
    }

    public SyntaxExpression getDirectory() {
        return directory;
    }

    public List<IdentifierPart> getTargetColumns() {
        return targetColumns;
    }

    public List<PartitionEntrySyntax> getPartitions() {
        return partitions;
    }
}
