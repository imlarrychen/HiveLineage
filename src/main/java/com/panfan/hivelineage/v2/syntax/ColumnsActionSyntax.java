package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class ColumnsActionSyntax extends AlterActionSyntax {
    private final ColumnChangeKindSyntax kind;
    private final List<ColumnDefinitionSyntax> columns;

    public ColumnsActionSyntax(ColumnChangeKindSyntax kind, List<ColumnDefinitionSyntax> columns) {
        super();
        this.kind = Objects.requireNonNull(kind, "kind");
        this.columns = ModelCollections.immutableList(columns);
    }

    public ColumnChangeKindSyntax getKind() {
        return kind;
    }

    public List<ColumnDefinitionSyntax> getColumns() {
        return columns;
    }
}
