package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;
import java.util.Objects;

public final class QueryClausesSyntax {
    private final SyntaxExpression where;
    private final GroupingSyntax grouping;
    private final SyntaxExpression having;
    private final List<NamedWindowSyntax> windows;
    private final QueryTailSyntax tail;

    public QueryClausesSyntax(
            SyntaxExpression where,
            GroupingSyntax grouping,
            SyntaxExpression having,
            List<NamedWindowSyntax> windows,
            QueryTailSyntax tail) {
        this.where = where;
        this.grouping = grouping;
        this.having = having;
        this.windows = ModelCollections.immutableList(windows);
        this.tail = Objects.requireNonNull(tail, "tail");
    }

    public SyntaxExpression getWhere() {
        return where;
    }

    public GroupingSyntax getGrouping() {
        return grouping;
    }

    public SyntaxExpression getHaving() {
        return having;
    }

    public List<NamedWindowSyntax> getWindows() {
        return windows;
    }

    public QueryTailSyntax getTail() {
        return tail;
    }
}
