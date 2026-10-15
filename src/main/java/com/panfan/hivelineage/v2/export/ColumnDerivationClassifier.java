package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.analysis.UnknownEndpoint;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

import java.util.List;
import java.util.Objects;

/**
 * Classifies the bound value expression; conditions and display boundaries do not transform a copied value.
 */
public final class ColumnDerivationClassifier {
    private ColumnDerivationClassifier() {
    }

    /** Source and binary compatibility bridge; classification never requests rendered text. */
    public static ColumnDerivationKind classify(BoundExpressionFormatter formatter, BoundExpression expression,
                                                List<Dependency> dependencies) {
        return classifyShape(Objects.requireNonNull(formatter, "formatter").shapes(), expression, dependencies);
    }

    public static ColumnDerivationKind classifyShape(ExpressionShapeAnalyzer shapes, BoundExpression expression,
                                                     List<Dependency> dependencies) {
        Objects.requireNonNull(shapes, "shapes");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(dependencies, "dependencies");
        boolean field = false;
        for (Dependency dependency : dependencies) {
            if (dependency.getSource() instanceof UnknownEndpoint) {
                return ColumnDerivationKind.UNKNOWN;
            }
            field |= dependency.getSource() instanceof ColumnEndpoint
                    && (dependency.getRole() == DependencyRole.VALUE || dependency.getRole() == DependencyRole.DEFINITION);
        }
        if (shapes.literalOnly(expression)) {
            return ColumnDerivationKind.CONSTANT;
        }
        return field && !shapes.complex(expression) ? ColumnDerivationKind.DIRECT : ColumnDerivationKind.DERIVED;
    }
}
