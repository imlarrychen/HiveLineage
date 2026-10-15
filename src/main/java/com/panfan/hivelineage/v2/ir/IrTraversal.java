package com.panfan.hivelineage.v2.ir;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSegment;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural traversal only. It neither binds names nor computes dependencies.
 */
public final class IrTraversal {
    private IrTraversal() {
    }

    public static List<OutputSlot> knownSlots(OutputSchema schema) {
        List<OutputSlot> slots = new ArrayList<OutputSlot>();
        for (OutputSegment segment : schema.getSegments()) {
            if (segment instanceof KnownOutputSegment) {
                slots.addAll(((KnownOutputSegment) segment).getSlots());
            }
        }
        return slots;
    }

    public static List<BoundExpression> children(BoundExpression expression) {
        List<BoundExpression> children = new ArrayList<BoundExpression>();
        if (expression instanceof Expressions.Call) {
            children.addAll(((Expressions.Call) expression).getArguments());
        } else if (expression instanceof Expressions.Case) {
            Expressions.Case value = (Expressions.Case) expression;
            for (Expressions.WhenThen branch : value.getBranches()) {
                children.add(branch.getCondition());
                children.add(branch.getValue());
            }
            value.getElseValue().ifPresent(children::add);
        } else if (expression instanceof Expressions.Cast) {
            children.add(((Expressions.Cast) expression).getExpression());
        } else if (expression instanceof Expressions.FieldAccess) {
            children.add(((Expressions.FieldAccess) expression).getExpression());
        } else if (expression instanceof Expressions.Subscript) {
            children.add(((Expressions.Subscript) expression).getExpression());
            children.add(((Expressions.Subscript) expression).getIndex());
        } else if (expression instanceof Expressions.InSubquery) {
            children.add(((Expressions.InSubquery) expression).getValue());
        } else if (expression instanceof Expressions.UnknownExpression) {
            children.addAll(((Expressions.UnknownExpression) expression).getKnownChildren());
        }
        return children;
    }

    public static List<BoundExpression> expressions(BoundRelation relation) {
        List<BoundExpression> expressions = new ArrayList<BoundExpression>();
        if (relation instanceof Relations.Values) {
            for (List<BoundExpression> row : ((Relations.Values) relation).getRows()) {
                expressions.addAll(row);
            }
        } else if (relation instanceof Relations.Project) {
            assignments(((Relations.Project) relation).getAssignments(), expressions);
        } else if (relation instanceof Relations.Filter) {
            expressions.add(((Relations.Filter) relation).getPredicate());
        } else if (relation instanceof Relations.Join) {
            Relations.Join join = (Relations.Join) relation;
            join.getCondition().ifPresent(expressions::add);
            for (Relations.JoinPredicate predicate : join.getPredicates()) {
                expressions.add(predicate.getExpression());
            }
        } else if (relation instanceof Relations.Aggregate) {
            Relations.Aggregate aggregate = (Relations.Aggregate) relation;
            expressions.addAll(aggregate.getGroupings());
            assignments(aggregate.getAssignments(), expressions);
        } else if (relation instanceof Relations.Window) {
            for (Relations.WindowComputation window : ((Relations.Window) relation).getComputations()) {
                expressions.add(window.getAssignment().getExpression());
                expressions.addAll(window.getPartitions());
                for (Relations.OrderKey key : window.getOrderKeys()) {
                    expressions.add(key.getExpression());
                }
                if (window.getFrame().isPresent()) {
                    window.getFrame().get().getStart().getOffset().ifPresent(expressions::add);
                    window.getFrame().get().getEnd().getOffset().ifPresent(expressions::add);
                }
            }
        } else if (relation instanceof Relations.Generate) {
            expressions.addAll(((Relations.Generate) relation).getArguments());
        } else if (relation instanceof Relations.Sort) {
            for (Relations.OrderKey key : ((Relations.Sort) relation).getOrderKeys()) {
                expressions.add(key.getExpression());
            }
        } else if (relation instanceof Relations.Limit) {
            Relations.Limit limit = (Relations.Limit) relation;
            expressions.add(limit.getCount());
            limit.getOffset().ifPresent(expressions::add);
        }
        return expressions;
    }

    private static void assignments(List<OutputAssignment> assignments, List<BoundExpression> expressions) {
        for (OutputAssignment assignment : assignments) {
            expressions.add(assignment.getExpression());
        }
    }
}
