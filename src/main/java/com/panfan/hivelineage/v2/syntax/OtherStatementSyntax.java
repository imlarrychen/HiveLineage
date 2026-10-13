package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import java.util.Objects;

public final class OtherStatementSyntax extends SyntaxStatement {
    private final String classification;
    private final String unsupportedReason;

    public OtherStatementSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            String classification,
            String unsupportedReason) {
        super(nodeId, sourceRef);
        this.classification = Objects.requireNonNull(classification, "classification");
        this.unsupportedReason = unsupportedReason;
    }

    public String getClassification() {
        return classification;
    }

    public String getUnsupportedReason() {
        return unsupportedReason;
    }

    @Override
    public StatementKind getKind() {
        return StatementKind.OTHER;
    }
}
