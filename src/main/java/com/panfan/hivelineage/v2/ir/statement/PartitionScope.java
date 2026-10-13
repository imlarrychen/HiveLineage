package com.panfan.hivelineage.v2.ir.statement;

import com.panfan.hivelineage.v2.common.ModelCollections;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import java.util.List;
import java.util.Objects;

public final class PartitionScope {
    public enum Kind { UNPARTITIONED, STATIC, DYNAMIC, MIXED, UNKNOWN }
    public enum AssignmentKind { STATIC, DYNAMIC }
    private final Kind kind;
    private final List<Assignment> assignments;

    public PartitionScope(Kind kind, List<Assignment> assignments) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.assignments = ModelCollections.immutableList(assignments);
    }

    public Kind getKind() {
        return kind;
    }

    public List<Assignment> getAssignments() {
        return assignments;
    }

    public static final class Assignment {
        private final FieldIdentity field;
        private final AssignmentKind kind;
        private final BoundExpression expression;

        public Assignment(FieldIdentity field, AssignmentKind kind, BoundExpression expression) {
            this.field = Objects.requireNonNull(field, "field");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.expression = Objects.requireNonNull(expression, "expression");
        }

        public FieldIdentity getField() {
            return field;
        }

        public AssignmentKind getKind() {
            return kind;
        }

        public BoundExpression getExpression() {
            return expression;
        }
    }
}
