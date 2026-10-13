package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

public interface TypeAnalyzer {
    TypeAnalysisResult analyze(BoundExpression expression, FunctionCatalog functions);
}
