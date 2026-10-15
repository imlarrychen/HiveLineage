package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;

import java.util.Optional;

/**
 * Complete condition structure and rendering range, never a text parsing callback.
 */
public final class JoinConditionSource {
    private final Optional<BoundExpression> predicate;
    private final Optional<SourceRef> completeConditionSource;

    public JoinConditionSource(Optional<BoundExpression> predicate, Optional<SourceRef> completeConditionSource) {
        this.predicate = ModelChecks.value(predicate, "predicate");
        this.completeConditionSource = ModelChecks.value(completeConditionSource, "completeConditionSource");
    }

    public Optional<BoundExpression> getPredicate() {
        return predicate;
    }

    public Optional<SourceRef> getCompleteConditionSource() {
        return completeConditionSource;
    }
}
