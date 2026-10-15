package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.List;

/** Owns window value, partition, order and frame-offset dependency semantics. */
final class WindowRelationRule implements EvaluatingRelationLineageRule {
    @Override
    public RelationFacts evaluate(BoundRelation relation, List<RelationFacts> inputs, RelationEvaluationContext context) {
        RelationFactBuilder facts = new RelationFactBuilder(relation, context);
        Relations.Window window = (Relations.Window) relation;
        facts.mappings(window.getRetainedSlots(), "window.passthrough");
        for (Relations.WindowComputation computation : window.getComputations()) {
            computation(facts, computation);
        }
        return facts.finish();
    }

    private void computation(RelationFactBuilder facts, Relations.WindowComputation computation) {
        SlotId target = computation.getAssignment().getOutput();
        List<Dependency> dependencies = new ArrayList<Dependency>(facts.expression(
                computation.getAssignment().getExpression(), target, "window.value", null));
        for (BoundExpression partition : computation.getPartitions()) {
            dependencies.addAll(facts.expression(partition, target, "window.partition", DependencyRole.CONDITION));
        }
        for (Relations.OrderKey order : computation.getOrderKeys()) {
            dependencies.addAll(facts.expression(order.getExpression(), target, "window.order", DependencyRole.CONDITION));
        }
        computation.getFrame().ifPresent(frame -> {
            frame.getStart().getOffset().ifPresent(offset -> dependencies.addAll(facts.expression(offset, target, "window.frame", DependencyRole.CONDITION)));
            frame.getEnd().getOffset().ifPresent(offset -> dependencies.addAll(facts.expression(offset, target, "window.frame", DependencyRole.CONDITION)));
        });
        facts.output(target, facts.id(), dependencies);
    }
}
