package com.panfan.hivelineage.v2.ir.relation;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.Objects;

public final class OutputAssignment {
    private final SlotId output;
    private final BoundExpression expression;

    public OutputAssignment(SlotId output, BoundExpression expression) {
        this.output = Objects.requireNonNull(output, "output");
        this.expression = Objects.requireNonNull(expression, "expression");
    }

    public SlotId getOutput() {
        return output;
    }

    public BoundExpression getExpression() {
        return expression;
    }
}
