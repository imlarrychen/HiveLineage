package com.panfan.hivelineage.v2.syntax;

import java.util.Objects;

public final class QueryInsertSourceSyntax extends InsertSourceSyntax {
    private final SyntaxQuery query;

    public QueryInsertSourceSyntax(SyntaxQuery query) {
        super();
        this.query = Objects.requireNonNull(query, "query");
    }

    public SyntaxQuery getQuery() {
        return query;
    }
}
