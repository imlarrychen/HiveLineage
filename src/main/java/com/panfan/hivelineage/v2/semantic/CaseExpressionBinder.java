package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.syntax.CaseBranchSyntax;
import com.panfan.hivelineage.v2.syntax.CaseSyntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class CaseExpressionBinder {
    private final BindingWork work;
    private final ExpressionBinder expressions;

    CaseExpressionBinder(BindingWork work, ExpressionBinder expressions) {
        this.work = work;
        this.expressions = expressions;
    }

    BoundExpression bind(CaseSyntax syntax, ReferenceScope scope) {
        NodeId id = work.id(syntax, scope, "expression");
        CaseSyntax cases = syntax;
        BoundExpression operand = cases.getOperand() == null ? null : expressions.bind(cases.getOperand(), scope);
        List<Expressions.WhenThen> branches = new ArrayList<>();
        for (CaseBranchSyntax branch : cases.getBranches()) {
            BoundExpression condition = expressions.bind(branch.getWhenExpression(), scope);
            if (operand != null) {
                condition = expressions.operator(new NodeId(condition.getId().getValue() + "/simple-case"), condition.getSourceRef().orElse(null),
                        "equal", Arrays.asList(operand, condition));
            }
            branches.add(new Expressions.WhenThen(condition, expressions.bind(branch.getThenExpression(), scope)));
        }
        return new Expressions.Case(id, TypeRef.unknown(), syntax.getSourceRef(), branches,
                cases.getElseExpression() == null ? null : expressions.bind(cases.getElseExpression(), scope));
    }
}
