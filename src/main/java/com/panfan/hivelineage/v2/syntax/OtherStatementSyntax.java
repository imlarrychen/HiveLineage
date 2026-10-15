package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class OtherStatementSyntax extends SyntaxStatement {
    private final String classification;
    private final String unsupportedReason;
    private final boolean dataChanging;

    public OtherStatementSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            String classification,
            String unsupportedReason) {
        this(nodeId, sourceRef, classification, unsupportedReason,
                unsupportedReason != null && !"INVALID_DDL".equals(unsupportedReason));
    }

    public OtherStatementSyntax(NodeId nodeId, SourceRef sourceRef, String classification,
                                String unsupportedReason, boolean dataChanging) {
        super(nodeId, sourceRef);
        this.dataChanging = dataChanging;
        this.classification = Objects.requireNonNull(classification, "classification");
        this.unsupportedReason = unsupportedReason;
    }

    public String getClassification() {
        return classification;
    }

    /**
     * Dialect-independent classification of an otherwise unsupported statement.
     */
    public boolean isDataChanging() {
        return dataChanging;
    }

    public String getUnsupportedReason() {
        return unsupportedReason;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.OTHER;
    }
}
