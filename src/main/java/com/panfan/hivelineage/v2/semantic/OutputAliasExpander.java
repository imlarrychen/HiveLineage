package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class OutputAliasExpander {
    private OutputAliasExpander() {
    }

    /** Output aliases are expressions when evaluated alongside the SELECT assignments. */
    static BoundExpression expand(BoundExpression expression, Map<SlotId, BoundExpression> assignments) {
        if (expression instanceof Expressions.SlotRef) {
            Expressions.SlotRef reference = (Expressions.SlotRef) expression;
            BoundExpression value = assignments.get(reference.getSlotId());
            return value == null || !reference.getFieldPath().isEmpty() ? expression : value;
        }
        NodeId id = expression.getId();
        TypeRef type = expression.getType();
        SourceRef source = expression.getSourceRef().orElse(null);
        if (expression instanceof Expressions.Call) {
            Expressions.Call call = (Expressions.Call) expression;
            List<BoundExpression> arguments = new ArrayList<>();
            for (BoundExpression argument : call.getArguments()) {
                arguments.add(expand(argument, assignments));
            }
            return new Expressions.Call(id, type, source, call.getFunction(), arguments, call.getDistinct());
        }
        if (expression instanceof Expressions.Case) {
            Expressions.Case value = (Expressions.Case) expression;
            List<Expressions.WhenThen> branches = new ArrayList<>();
            for (Expressions.WhenThen branch : value.getBranches()) {
                branches.add(new Expressions.WhenThen(expand(branch.getCondition(), assignments),
                    expand(branch.getValue(), assignments)));
            }
            return new Expressions.Case(id, type, source, branches,
                value.getElseValue().isPresent() ? expand(value.getElseValue().get(), assignments) : null);
        }
        if (expression instanceof Expressions.Cast) {
            Expressions.Cast value = (Expressions.Cast) expression;
            return new Expressions.Cast(id, type, source, expand(value.getExpression(), assignments), value.getTargetType());
        }
        if (expression instanceof Expressions.FieldAccess) {
            Expressions.FieldAccess value = (Expressions.FieldAccess) expression;
            return new Expressions.FieldAccess(id, type, source, expand(value.getExpression(), assignments), value.getField());
        }
        if (expression instanceof Expressions.Subscript) {
            Expressions.Subscript value = (Expressions.Subscript) expression;
            return new Expressions.Subscript(id, type, source, expand(value.getExpression(), assignments),
                expand(value.getIndex(), assignments));
        }
        if (expression instanceof Expressions.InSubquery) {
            Expressions.InSubquery value = (Expressions.InSubquery) expression;
            return new Expressions.InSubquery(id, type, source, expand(value.getValue(), assignments),
                value.getQueryRoot(), value.getResultSlot(), value.getNegated());
        }
        if (expression instanceof Expressions.UnknownExpression) {
            Expressions.UnknownExpression value = (Expressions.UnknownExpression) expression;
            List<BoundExpression> children = new ArrayList<>();
            for (BoundExpression child : value.getKnownChildren()) {
                children.add(expand(child, assignments));
            }
            return new Expressions.UnknownExpression(id, type, source, children, value.getDiagnosticCode());
        }
        return expression;
    }

}
