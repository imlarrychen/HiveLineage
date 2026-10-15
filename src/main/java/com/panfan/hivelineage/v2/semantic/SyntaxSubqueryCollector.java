package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.syntax.BetweenSyntax;
import com.panfan.hivelineage.v2.syntax.BinarySyntax;
import com.panfan.hivelineage.v2.syntax.CallArgumentsSyntax;
import com.panfan.hivelineage.v2.syntax.CallSyntax;
import com.panfan.hivelineage.v2.syntax.CaseBranchSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;
import com.panfan.hivelineage.v2.syntax.CastSyntax;
import com.panfan.hivelineage.v2.syntax.FieldAccessSyntax;
import com.panfan.hivelineage.v2.syntax.InListSyntax;
import com.panfan.hivelineage.v2.syntax.QueryClausesSyntax;
import com.panfan.hivelineage.v2.syntax.SortItemSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SubscriptSyntax;
import com.panfan.hivelineage.v2.syntax.SyntaxExpression;
import com.panfan.hivelineage.v2.syntax.UnarySyntax;
import com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.WindowCallSyntax;
import com.panfan.hivelineage.v2.syntax.WindowSpecificationSyntax;
import java.util.List;

/** Traverses object dependencies without demanding output schema or field metadata. */
final class SyntaxSubqueryCollector {
    private final BindingWork work;
    private final QueryBinder queries;

    SyntaxSubqueryCollector(BindingWork work, QueryBinder queries) {
        this.work = work;
        this.queries = queries;
    }

    void collect(SyntaxExpression expression, ReferenceScope scope, List<NodeId> roots) {
        if (expression instanceof SubqueryExpressionSyntax) {
            SubqueryExpressionSyntax subquery = (SubqueryExpressionSyntax) expression;
            String suffix = subquery.getSubqueryKind().name().toLowerCase(java.util.Locale.ROOT);
            String path = scope.displayPath + "." + scope.clauseLocation + "." + suffix + "[" + (++scope.childSequence) + "]";
            BoundRelation relation = queries.query(subquery.getQuery(), scope, false, QueryResult.Role.SUBQUERY,
                scope.prefix, null, path);
            roots.add(relation.getId());
            collect(subquery.getProbe(), scope, roots);
        } else if (expression instanceof BinarySyntax) {
            collect(((BinarySyntax) expression).getLeft(), scope, roots);
            collect(((BinarySyntax) expression).getRight(), scope, roots);
        } else if (expression instanceof UnarySyntax) {
            collect(((UnarySyntax) expression).getOperand(), scope, roots);
        } else if (expression instanceof CallSyntax) {
            CallArgumentsSyntax arguments = ((CallSyntax) expression).getArguments();
            for (SyntaxExpression argument : arguments.getArguments()) {
                collect(argument, scope, roots);
            }
            collect(arguments.getFilter(), scope, roots);
        } else if (expression instanceof CastSyntax) {
            collect(((CastSyntax) expression).getExpression(), scope, roots);
        } else if (expression instanceof BetweenSyntax) {
            BetweenSyntax between = (BetweenSyntax) expression;
            collect(between.getExpression(), scope, roots);
            collect(between.getLowerBound(), scope, roots);
            collect(between.getUpperBound(), scope, roots);
        } else if (expression instanceof InListSyntax) {
            InListSyntax in = (InListSyntax) expression;
            collect(in.getExpression(), scope, roots);
            for (SyntaxExpression value : in.getValues()) {
                collect(value, scope, roots);
            }
        } else if (expression instanceof SubscriptSyntax) {
            collect(((SubscriptSyntax) expression).getBase(), scope, roots);
            collect(((SubscriptSyntax) expression).getIndex(), scope, roots);
        } else if (expression instanceof FieldAccessSyntax) {
            collect(((FieldAccessSyntax) expression).getBase(), scope, roots);
        } else if (expression instanceof WindowCallSyntax) {
            WindowCallSyntax call = (WindowCallSyntax) expression;
            collect(call.getCall(), scope, roots);
            WindowSpecificationSyntax window = WindowSpecificationResolver.resolve(call.getWindow(), scope, work, call.getSourceRef());
            for (SyntaxExpression partition : window.getPartitionBy()) {
                collect(partition, scope, roots);
            }
            for (SortItemSyntax order : window.getOrderBy()) {
                collect(order.getExpression(), scope, roots);
            }
            if (window.getFrame() != null) {
                collect(window.getFrame().getStart().getOffset(), scope, roots);
                if (window.getFrame().getEnd() != null) {
                    collect(window.getFrame().getEnd().getOffset(), scope, roots);
                }
            }
        } else if (expression instanceof UnknownExpressionSyntax) {
            for (SyntaxExpression child : ((UnknownExpressionSyntax) expression).getKnownChildren()) {
                collect(child, scope, roots);
            }
        } else if (expression instanceof CaseSyntax) {
            CaseSyntax cases = (CaseSyntax) expression;
            collect(cases.getOperand(), scope, roots);
            for (CaseBranchSyntax branch : cases.getBranches()) {
                collect(branch.getWhenExpression(), scope, roots);
                collect(branch.getThenExpression(), scope, roots);
            }
            collect(cases.getElseExpression(), scope, roots);
        }
    }

    void collectClauses(QueryClausesSyntax clauses, ReferenceScope scope, List<NodeId> roots, boolean includeBoundClauses) {
        String previous = scope.clauseLocation;
        if (includeBoundClauses) {
            scope.clauseLocation = "group_by";
            if (clauses.getGrouping() != null) {
                for (List<SyntaxExpression> group : clauses.getGrouping().getSets()) {
                    for (SyntaxExpression expression : group) {
                        collect(expression, scope, roots);
                    }
                }
            }
            scope.clauseLocation = "order_by";
            for (SortItemSyntax order : clauses.getTail().getOrderBy()) {
                collect(order.getExpression(), scope, roots);
            }
            scope.clauseLocation = "sort_by";
            for (SortItemSyntax order : clauses.getTail().getSortBy()) {
                collect(order.getExpression(), scope, roots);
            }
        }
        scope.clauseLocation = "distribute_by";
        for (SyntaxExpression expression : clauses.getTail().getDistributeBy()) {
            collect(expression, scope, roots);
        }
        scope.clauseLocation = "cluster_by";
        for (SyntaxExpression expression : clauses.getTail().getClusterBy()) {
            collect(expression, scope, roots);
        }
        scope.clauseLocation = previous;
    }
}
