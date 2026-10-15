package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

import java.util.Optional;

public final class Dependency {
    private final LineageEndpoint source;
    private final LineageEndpoint target;
    private final DependencyRole role;
    private final DependencyPrecision precision;
    private final Optional<BoundExpression> condition;
    private final Evidence evidence;

    public Dependency(LineageEndpoint source, LineageEndpoint target, DependencyRole role, DependencyPrecision precision, Optional<BoundExpression> condition, Evidence evidence) {
        this.source = ModelChecks.value(source, "source");
        this.target = ModelChecks.value(target, "target");
        this.role = ModelChecks.value(role, "role");
        this.precision = ModelChecks.value(precision, "precision");
        this.condition = ModelChecks.value(condition, "condition");
        this.evidence = ModelChecks.value(evidence, "evidence");
    }

    public LineageEndpoint getSource() {
        return source;
    }

    public LineageEndpoint getTarget() {
        return target;
    }

    public DependencyRole getRole() {
        return role;
    }

    public DependencyPrecision getPrecision() {
        return precision;
    }

    public Optional<BoundExpression> getCondition() {
        return condition;
    }

    public Evidence getEvidence() {
        return evidence;
    }
}
