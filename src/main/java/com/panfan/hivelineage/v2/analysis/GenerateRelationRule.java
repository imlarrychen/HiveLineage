package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns generator value, rowset and opaque-behavior semantics. */
final class GenerateRelationRule implements EvaluatingRelationLineageRule {
    @Override
    public RelationFacts evaluate(BoundRelation relation, List<RelationFacts> inputs, RelationEvaluationContext context) {
        RelationFactBuilder facts = new RelationFactBuilder(relation, context);
        Relations.Generate generate = (Relations.Generate) relation;
        facts.mappings(generate.getRetainedSlots(), "generate.passthrough");
        List<Dependency> arguments = new ArrayList<Dependency>();
        for (BoundExpression argument : generate.getArguments()) {
            arguments.addAll(facts.resolve(argument).getDependencies());
        }
        for (SlotId target : generate.getGeneratedSlots()) {
            output(facts, generate, target, arguments);
        }
        for (BoundExpression argument : generate.getArguments()) {
            facts.predicate(argument, "generate.rowset");
        }
        return facts.finish();
    }

    private void output(RelationFactBuilder facts, Relations.Generate generate, SlotId target, List<Dependency> arguments) {
        List<Dependency> dependencies = new ArrayList<Dependency>(facts.retarget(arguments, new SlotEndpoint(target), "generate.value", null));
        dependencies.addAll(facts.retarget(facts.rows(), new SlotEndpoint(target), "generate.rows", DependencyRole.ROWSET));
        if (generate.getFunction().getKind() == FunctionSymbol.FunctionKind.OPAQUE) {
            dependencies.add(facts.evidence().dependency(new UnknownEndpoint(FactSupport.diagnostic("OPAQUE_GENERATOR", "Generator behavior is unknown", null)),
                    new SlotEndpoint(target), DependencyRole.VALUE, DependencyPrecision.UNKNOWN, facts.id(), "generate.unknown", Collections.emptyList(), generate.getSourceRef()));
        }
        facts.output(target, facts.id(), dependencies);
    }
}
