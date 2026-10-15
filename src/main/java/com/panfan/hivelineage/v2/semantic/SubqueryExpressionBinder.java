package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.statement.QueryResult;
import com.panfan.hivelineage.v2.syntax.SubqueryExpressionSyntax;
import com.panfan.hivelineage.v2.syntax.SubqueryKindSyntax;

import java.util.Collections;
import java.util.List;

/** Scoped queries return their dependencies even when their scalar width is unknown. */
final class SubqueryExpressionBinder {
    private final BindingWork work;
    private final ExpressionBinder expressions;

    SubqueryExpressionBinder(BindingWork work, ExpressionBinder expressions) {
        this.work = work;
        this.expressions = expressions;
    }

    BoundExpression bind(SubqueryExpressionSyntax syntax, ReferenceScope scope) {
        NodeId id = work.id(syntax, scope, "expression");
        SourceRef source = syntax.getSourceRef();
        SubqueryExpressionSyntax subquery = syntax;
        String suffix = subquery.getSubqueryKind().name().toLowerCase(java.util.Locale.ROOT);
        String path = scope.displayPath + "." + scope.clauseLocation + "." + suffix + "[" + (++scope.childSequence) + "]";
        BoundRelation relation = new QueryBinder(work).query(subquery.getQuery(), scope,
                subquery.getSubqueryKind() != SubqueryKindSyntax.EXISTS, QueryResult.Role.SUBQUERY, scope.prefix, null, path);
        if (subquery.getSubqueryKind() == SubqueryKindSyntax.EXISTS) {
            return new Expressions.ExistsSubquery(id, SemanticTypes.type(TypeRef.Kind.BOOLEAN), source, relation.getId(), false);
        }
        List<OutputSlot> slots = BindingWork.slots(relation.getOutputSchema());
        if (slots.size() != 1) {
            work.warn("SCALAR_SUBQUERY_WIDTH_UNKNOWN", "Subquery must expose one known output", source);
            return new Expressions.UnknownExpression(id, TypeRef.unknown(), source,
                    Collections.<BoundExpression>singletonList(new Expressions.ExistsSubquery(
                            new NodeId(id.getValue() + "/rows"), SemanticTypes.type(TypeRef.Kind.BOOLEAN), source, relation.getId(), false)),
                    "SCALAR_SUBQUERY_WIDTH_UNKNOWN");
        }
        if (subquery.getSubqueryKind() == SubqueryKindSyntax.IN) {
            return new Expressions.InSubquery(id, SemanticTypes.type(TypeRef.Kind.BOOLEAN), source,
                    expressions.bind(subquery.getProbe(), scope), relation.getId(), slots.get(0).getId(), false);
        }
        return new Expressions.ScalarSubquery(id, slots.get(0).getType(), source, relation.getId(),
                slots.get(0).getId(), Expressions.ScalarCardinality.UNKNOWN);
    }
}
