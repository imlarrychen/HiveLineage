package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.ModelCollections;

import java.util.List;

public final class WindowSpecificationSyntax {
    private final IdentifierPart baseWindowName;
    private final List<SyntaxExpression> partitionBy;
    private final List<SortItemSyntax> orderBy;
    private final WindowFrameSyntax frame;

    public WindowSpecificationSyntax(
            IdentifierPart baseWindowName,
            List<SyntaxExpression> partitionBy,
            List<SortItemSyntax> orderBy,
            WindowFrameSyntax frame) {
        this.baseWindowName = baseWindowName;
        this.partitionBy = ModelCollections.immutableList(partitionBy);
        this.orderBy = ModelCollections.immutableList(orderBy);
        this.frame = frame;
    }

    public IdentifierPart getBaseWindowName() {
        return baseWindowName;
    }

    public List<SyntaxExpression> getPartitionBy() {
        return partitionBy;
    }

    public List<SortItemSyntax> getOrderBy() {
        return orderBy;
    }

    public WindowFrameSyntax getFrame() {
        return frame;
    }
}
