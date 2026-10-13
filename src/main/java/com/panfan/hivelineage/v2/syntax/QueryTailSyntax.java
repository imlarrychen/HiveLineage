package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;

public final class QueryTailSyntax {
    private final List<SortItemSyntax> orderBy;
    private final List<SyntaxExpression> distributeBy;
    private final List<SortItemSyntax> sortBy;
    private final List<SyntaxExpression> clusterBy;
    private final LimitSyntax limit;

    public QueryTailSyntax(
            List<SortItemSyntax> orderBy,
            List<SyntaxExpression> distributeBy,
            List<SortItemSyntax> sortBy,
            List<SyntaxExpression> clusterBy,
            LimitSyntax limit) {
        this.orderBy = ModelCollections.immutableList(orderBy);
        this.distributeBy = ModelCollections.immutableList(distributeBy);
        this.sortBy = ModelCollections.immutableList(sortBy);
        this.clusterBy = ModelCollections.immutableList(clusterBy);
        this.limit = limit;
    }

    public List<SortItemSyntax> getOrderBy() {
        return orderBy;
    }

    public List<SyntaxExpression> getDistributeBy() {
        return distributeBy;
    }

    public List<SortItemSyntax> getSortBy() {
        return sortBy;
    }

    public List<SyntaxExpression> getClusterBy() {
        return clusterBy;
    }

    public LimitSyntax getLimit() {
        return limit;
    }
}
