package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import java.util.Objects;

/** Target identity is resolved before lineage; expression contains no source-column result. */
public final class WriteAssignment {
    private final FieldIdentity targetField;
    private final BoundExpression expression;

    public WriteAssignment(FieldIdentity targetField, BoundExpression expression) {
        this.targetField = Objects.requireNonNull(targetField, "targetField");
        this.expression = Objects.requireNonNull(expression, "expression");
    }

    public FieldIdentity getTargetField() {
        return targetField;
    }

    public BoundExpression getExpression() {
        return expression;
    }
}
