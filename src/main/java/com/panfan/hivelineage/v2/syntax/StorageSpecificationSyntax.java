package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class StorageSpecificationSyntax {
    private final SyntaxName fileFormat;
    private final SerdeSyntax serde;
    private final DelimitedRowFormatSyntax rowFormat;
    private final List<IdentifierPart> bucketColumns;
    private final List<SortItemSyntax> sortedBy;
    private final SyntaxExpression bucketCount;

    public StorageSpecificationSyntax(
            SyntaxName fileFormat,
            SerdeSyntax serde,
            DelimitedRowFormatSyntax rowFormat,
            List<IdentifierPart> bucketColumns,
            List<SortItemSyntax> sortedBy,
            SyntaxExpression bucketCount) {
        this.fileFormat = fileFormat;
        this.serde = serde;
        this.rowFormat = rowFormat;
        this.bucketColumns = ModelCollections.immutableList(bucketColumns);
        this.sortedBy = ModelCollections.immutableList(sortedBy);
        this.bucketCount = bucketCount;
    }

    public SyntaxName getFileFormat() {
        return fileFormat;
    }

    public SerdeSyntax getSerde() {
        return serde;
    }

    public DelimitedRowFormatSyntax getRowFormat() {
        return rowFormat;
    }

    public List<IdentifierPart> getBucketColumns() {
        return bucketColumns;
    }

    public List<SortItemSyntax> getSortedBy() {
        return sortedBy;
    }

    public SyntaxExpression getBucketCount() {
        return bucketCount;
    }
}
