package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

public interface TypeAnalyzer {
    TypeAnalysisResult analyze(BoundExpression expression, FunctionCatalog functions);

    default TypeAnalysisResult analyze(BoundExpression expression, FunctionCatalog functions, String semanticVersion) {
        return analyze(expression, functions);
    }
}
