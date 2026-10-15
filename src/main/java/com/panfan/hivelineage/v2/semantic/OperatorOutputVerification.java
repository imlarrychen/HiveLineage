package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** Common output invariants and typed operator constraints have separate owners. */
final class OperatorOutputVerification {
    private final VerificationContext context;
    private final VerificationDiagnostics diagnostics;
    private final Map<Class<?>, BiConsumer<BoundRelation, OperatorOutputState>> rules = new LinkedHashMap<>();

    OperatorOutputVerification(VerificationContext context, VerificationDiagnostics diagnostics) {
        this.context = context;
        this.diagnostics = diagnostics;
        add(ExternalDatasetRelation.class, this::external);
        add(Relations.Values.class, this::values);
        add(Relations.Project.class, (relation, state) -> state.assignments(relation.getAssignments()));
        add(Relations.Aggregate.class, (relation, state) -> state.assignments(relation.getAssignments()));
        add(Relations.Window.class, this::window);
        add(Relations.Generate.class, (relation, state) -> state.generated(relation.getGeneratedSlots()));
        add(Relations.SetOp.class, new SetOperatorVerification(context, diagnostics)::verify);
        add(Relations.Join.class, this::join);
    }

    void verify(BoundRelation relation) {
        OperatorOutputState state = new OperatorOutputState(relation, context, diagnostics);
        state.mappings();
        BiConsumer<BoundRelation, OperatorOutputState> rule = rules.get(relation.getClass());
        if (rule != null) {
            rule.accept(relation, state);
        }
        state.complete();
    }

    private <R extends BoundRelation> void add(Class<R> type, BiConsumer<R, OperatorOutputState> rule) {
        rules.put(type, (relation, state) -> rule.accept(type.cast(relation), state));
    }

    private void external(ExternalDatasetRelation relation, OperatorOutputState state) {
        if (!relation.getInputIds().isEmpty() || state.width() != 0 || relation.getOutputSchema().getState() == SchemaState.KNOWN) {
            diagnostics.error("IR_EXTERNAL_DATASET_SHAPE", "External storage input must be a leaf with unclaimed output fields");
        }
    }

    private void values(Relations.Values relation, OperatorOutputState state) {
        for (List<BoundExpression> row : relation.getRows()) {
            if (relation.getOutputSchema().getState() == SchemaState.KNOWN && row.size() != state.width()) {
                diagnostics.error("IR_VALUES_WIDTH_MISMATCH", "Values row width differs from declared output");
            }
        }
        state.allProduced();
    }

    private void window(Relations.Window relation, OperatorOutputState state) {
        for (Relations.WindowComputation computation : relation.getComputations()) {
            state.assignments(Collections.singletonList(computation.getAssignment()));
        }
    }

    private void join(Relations.Join join, OperatorOutputState state) {
        if ((join.getJoinType() == Relations.JoinType.LEFT_SEMI || join.getJoinType() == Relations.JoinType.LEFT_ANTI)
                && context.relations().containsKey(join.getRight())) {
            for (SlotMapping mapping : join.getMappings()) {
                if (context.outputs(join.getRight()).contains(mapping.getInput())) {
                    diagnostics.error("IR_SEMI_JOIN_OUTPUT", "Semi/anti join exposes the right side");
                }
            }
        }
    }
}
