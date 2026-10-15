package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

import java.util.List;

public interface ExpressionLineageRule {
    ExpressionFacts analyze(BoundExpression expression, List<ExpressionFacts> arguments, LineageRuleContext context);
}
