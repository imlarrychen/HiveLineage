package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Objects;

public final class MergeInsertSyntax extends MergeActionSyntax {
    private final List<IdentifierPart> targetColumns;
    private final ValuesRowSyntax row;

    public MergeInsertSyntax(List<IdentifierPart> targetColumns, ValuesRowSyntax row) {
        super();
        this.targetColumns = ModelCollections.immutableList(targetColumns);
        this.row = Objects.requireNonNull(row, "row");
    }

    public List<IdentifierPart> getTargetColumns() {
        return targetColumns;
    }

    public ValuesRowSyntax getRow() {
        return row;
    }
}
