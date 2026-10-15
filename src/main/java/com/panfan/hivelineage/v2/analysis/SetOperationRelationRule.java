package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Set output lineage is evaluated branch by branch in the corresponding input environment. */
final class SetOperationRelationRule implements EvaluatingRelationLineageRule {
    @Override
    public RelationFacts evaluate(BoundRelation relation, List<RelationFacts> inputs, RelationEvaluationContext context) {
        RelationFactBuilder facts = new RelationFactBuilder(relation, context);
        Relations.SetOp set = (Relations.SetOp) relation;
        for (Relations.SetOutputMapping mapping : set.getMappings()) {
            List<Dependency> dependencies = new ArrayList<Dependency>();
            for (int branch = 0; branch < mapping.getBranchSlots().size(); branch++) {
                dependencies.addAll(branch(facts, set, mapping, inputs, branch));
            }
            facts.output(mapping.getOutput(), facts.id(), dependencies);
        }
        return facts.finish();
    }

    private List<Dependency> branch(RelationFactBuilder facts, Relations.SetOp set, Relations.SetOutputMapping mapping,
                                     List<RelationFacts> inputs, int index) {
        Relations.BranchSlot branch = mapping.getBranchSlots().get(index);
        if (branch.getSlot().isPresent()) {
            ExpressionFacts source = inputs.get(index).getOutputFacts().get(branch.getSlot().get());
            return facts.retarget(source.getDependencies(), new SlotEndpoint(mapping.getOutput()),
                    "set." + set.getOperator() + "." + set.getDuplicatePolicy(), null);
        }
        return Collections.singletonList(facts.evidence().dependency(new UnknownEndpoint(FactSupport.diagnostic(
                        branch.getDiagnosticCode().orElse("SET_BRANCH_UNKNOWN"), "Set branch output is unknown", null)),
                new SlotEndpoint(mapping.getOutput()), DependencyRole.VALUE, DependencyPrecision.UNKNOWN,
                facts.id(), "set.unknown", Collections.emptyList(), set.getSourceRef()));
    }
}
