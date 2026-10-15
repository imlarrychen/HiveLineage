package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class ValuesInsertSourceSyntax extends InsertSourceSyntax {
    private final List<ValuesRowSyntax> rows;

    public ValuesInsertSourceSyntax(List<ValuesRowSyntax> rows) {
        super();
        this.rows = ModelCollections.immutableList(rows);
    }

    public List<ValuesRowSyntax> getRows() {
        return rows;
    }
}
