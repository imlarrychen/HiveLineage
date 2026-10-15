package com.panfan.hivelineage.v2.syntax;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.NodeId;

import java.util.Objects;

public final class JoinFromSyntax extends FromSyntax {
    private final JoinOperandsSyntax operands;
    private final JoinTypeSyntax joinType;
    private final JoinConditionSyntax condition;

    public JoinFromSyntax(
            NodeId nodeId,
            SourceRef sourceRef,
            JoinOperandsSyntax operands,
            JoinTypeSyntax joinType,
            JoinConditionSyntax condition) {
        super(nodeId, sourceRef);
        this.operands = Objects.requireNonNull(operands, "operands");
        this.joinType = Objects.requireNonNull(joinType, "joinType");
        this.condition = Objects.requireNonNull(condition, "condition");
    }

    public JoinOperandsSyntax getOperands() {
        return operands;
    }

    public JoinTypeSyntax getJoinType() {
        return joinType;
    }

    public JoinConditionSyntax getCondition() {
        return condition;
    }

    @Override
    public FromKindSyntax getKind() {
        return FromKindSyntax.JOIN;
    }
}
