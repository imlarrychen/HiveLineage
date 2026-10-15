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

    public static ColumnDerivationKind classify(BoundExpressionFormatter formatter, BoundExpression expression,
                                                List<Dependency> dependencies) {
        Objects.requireNonNull(formatter, "formatter");
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
        if (formatter.literalOnly(expression)) {
            return ColumnDerivationKind.CONSTANT;
        }
        return field && !formatter.complex(expression) ? ColumnDerivationKind.DIRECT : ColumnDerivationKind.DERIVED;
    }
}
