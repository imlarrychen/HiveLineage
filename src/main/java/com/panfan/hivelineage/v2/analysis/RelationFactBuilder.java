package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns output accumulation, evidence targeting and result finalization, without operator algorithms. */
final class RelationFactBuilder {
    private final BoundRelation relation;
    private final RelationEvaluationContext context;
    private final Map<SlotId, ExpressionFacts> outputs = new LinkedHashMap<SlotId, ExpressionFacts>();
    private final List<Dependency> rows;
    private final RowSetEndpoint rowTarget;
    private RelationEvaluationContext.Environment environment;
    private boolean environmentDirty;

    RelationFactBuilder(BoundRelation relation, RelationEvaluationContext context) {
        this.relation = relation;
        this.context = context;
        this.rows = new ArrayList<Dependency>(context.getInputRows());
        this.environment = context.getEnvironment();
        this.rowTarget = new RowSetEndpoint(new RelationInstanceId("rowset:" + relation.getId().getValue()));
    }

    BoundRelation relation() {
        return relation;
    }

    NodeId id() {
        return relation.getId();
    }

    EvidenceFactory evidence() {
        return context.getEvidenceFactory();
    }

    Map<SlotId, ExpressionFacts> slots() {
        return context.getInputSlots();
    }

    List<Dependency> rows() {
        return Collections.unmodifiableList(new ArrayList<Dependency>(rows));
    }

    RowSetEndpoint rowTarget() {
        return rowTarget;
    }

    ExpressionFacts resolve(BoundExpression expression) {
        if (environmentDirty) {
            environment = context.getEnvironment().withRows(rows);
            environmentDirty = false;
        }
        return context.resolve(expression, environment);
    }

    ExpressionFacts output(SlotId slot) {
        return outputs.get(slot);
    }

    void output(SlotId slot, NodeId expression, List<Dependency> dependencies) {
        outputs.put(slot, RelationFactsEngine.facts(expression, dependencies));
    }

    void addRows(List<Dependency> dependencies) {
        rows.addAll(dependencies);
        environmentDirty |= !dependencies.isEmpty();
    }

    void addRow(Dependency dependency) {
        rows.add(dependency);
        environmentDirty = true;
    }

    List<Dependency> retarget(List<Dependency> dependencies, LineageEndpoint target, String rule, DependencyRole role) {
        return evidence().retarget(dependencies, target, id(), rule, role, relation.getSourceRef());
    }

    List<Dependency> expression(BoundExpression expression, SlotId target, String rule, DependencyRole role) {
        return retarget(resolve(expression).getDependencies(), new SlotEndpoint(target), rule, role);
    }

    void mappings(List<SlotMapping> mappings, String rule) {
        for (SlotMapping mapping : mappings) {
            List<Dependency> dependencies = retarget(slots().get(mapping.getInput()).getDependencies(),
                    new SlotEndpoint(mapping.getOutput()), rule, null);
            output(mapping.getOutput(), id(), dependencies);
        }
    }

    void assignments(List<OutputAssignment> assignments, String rule) {
        for (OutputAssignment assignment : assignments) {
            output(assignment.getOutput(), assignment.getExpression().getId(),
                    expression(assignment.getExpression(), assignment.getOutput(), rule, null));
        }
    }

    void predicate(BoundExpression expression, String rule) {
        List<Dependency> proof = resolve(expression).getDependencies();
        addRows(retarget(proof, rowTarget, rule, DependencyRole.CONDITION));
    }

    RelationFacts finish() {
        if (RelationFactsEngine.hasUnknownWidth(relation)) {
            addRow(evidence().dependency(new UnknownEndpoint(FactSupport.diagnostic("OUTPUT_SCHEMA_UNKNOWN", "Relation has uncertain output width", null)),
                    rowTarget, DependencyRole.SCHEMA, DependencyPrecision.UNKNOWN, id(), "schema.unknown", Collections.emptyList(), relation.getSourceRef()));
        }
        return new RelationFacts(id(), outputs, FactSupport.distinct(rows), FactSupport.diagnostics(rows));
    }
}
