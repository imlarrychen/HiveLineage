package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class DefinitionSchemaSyntax {
    private final List<ColumnDefinitionSyntax> columns;
    private final List<ColumnDefinitionSyntax> partitionColumns;
    private final StorageSpecificationSyntax storage;
    private final SyntaxName likeTable;

    public DefinitionSchemaSyntax(
            List<ColumnDefinitionSyntax> columns,
            List<ColumnDefinitionSyntax> partitionColumns,
            StorageSpecificationSyntax storage,
            SyntaxName likeTable) {
        this.columns = ModelCollections.immutableList(columns);
        this.partitionColumns = ModelCollections.immutableList(partitionColumns);
        this.storage = storage;
        this.likeTable = likeTable;
    }

    public List<ColumnDefinitionSyntax> getColumns() {
        return columns;
    }

    public List<ColumnDefinitionSyntax> getPartitionColumns() {
        return partitionColumns;
    }

    public StorageSpecificationSyntax getStorage() {
        return storage;
    }

    public SyntaxName getLikeTable() {
        return likeTable;
    }
}
