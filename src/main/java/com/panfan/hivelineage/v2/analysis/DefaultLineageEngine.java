package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.UnknownMapping;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure verified-IR dependency propagation. Every invocation owns its memoized state.
 */
public final class DefaultLineageEngine implements LineageEngine {
    private final int maxEvidenceEdges;

    public DefaultLineageEngine() {
        this(Integer.MAX_VALUE);
    }

    public DefaultLineageEngine(int maxEvidenceEdges) {
        if (maxEvidenceEdges < 0) {
            throw new IllegalArgumentException("maxEvidenceEdges must be nonnegative");
        }
        this.maxEvidenceEdges = maxEvidenceEdges;
    }

    public LineageFacts analyze(VerifiedAnalysisUnit unit) {
        return analyze(unit, new DefaultLineageRuleRegistry());
    }

    @Override
    public LineageFacts analyze(VerifiedAnalysisUnit verified, LineageRuleRegistry rules) {
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(rules, "rules");
        if (!verified.getCertificate().coversWholeUnit()) {
            throw new IllegalArgumentException("NEEDS_BINDING: whole-unit lineage requires a whole-unit verification claim");
        }
        if (!verified.getCertificate().getRequirements().contains(BindingRequirement.OBJECTS)) {
            throw new IllegalArgumentException("NEEDS_BINDING: object binding was not verified");
        }
        BoundAnalysisUnit unit = verified.getUnit();
        RelationFactsEngine engine = new RelationFactsEngine(unit, rules, maxEvidenceEdges);
        List<RelationFacts> relations = new ArrayList<RelationFacts>();
        List<Dependency> dependencies = new ArrayList<Dependency>();
        List<Dependency> all = new ArrayList<Dependency>();
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        for (BoundRelation relation : unit.getRelations()) {
            RelationFacts facts = engine.relation(relation.getId());
            relations.add(facts);
            all.addAll(facts.getRowSetFacts());
            for (ExpressionFacts expression : facts.getOutputFacts().values()) {
                all.addAll(expression.getDependencies());
            }
        }
        for (BoundWrite write : unit.getWrites()) {
            List<Dependency> rows = new ArrayList<Dependency>();
            Map<SlotId, ExpressionFacts> slots = new LinkedHashMap<SlotId, ExpressionFacts>();
            if (write.getSourceRelation().isPresent()) {
                RelationFacts input = engine.relation(write.getSourceRelation().get());
                rows.addAll(input.getRowSetFacts());
                slots.putAll(input.getOutputFacts());
            }
            RowSetEndpoint writeRows = new RowSetEndpoint(new RelationInstanceId("write:" + write.getId().getValue()));
            dependencies.addAll(engine.support().retarget(rows, writeRows, write.getId(), "write.rows", null, java.util.Optional.empty()));
            List<Dependency> conditionDependencies = write.getCondition()
                    .map(condition -> engine.expression(condition, slots, rows).getDependencies()).orElse(Collections.emptyList());
            for (WriteAssignment assignment : write.getAssignments()) {
                ColumnEndpoint target = new ColumnEndpoint(write.getTargetObject(), assignment.getTargetField().getId(), Collections.emptyList());
                ExpressionFacts facts = engine.expression(assignment.getExpression(), slots, rows);
                dependencies.addAll(engine.support().retarget(facts.getDependencies(), target, write.getId(), "write.value", null, assignment.getExpression().getSourceRef()));
                if (write.getCondition().isPresent()) {
                    BoundExpression condition = write.getCondition().get();
                    dependencies.addAll(engine.support().retarget(conditionDependencies, target,
                            write.getId(), "write.condition", DependencyRole.CONDITION, condition.getSourceRef()));
                }
            }
            for (PartitionScope.Assignment partition : write.getPartitionScope().getAssignments()) {
                ColumnEndpoint target = new ColumnEndpoint(write.getTargetObject(), partition.getField().getId(), Collections.emptyList());
                dependencies.addAll(engine.support().retarget(engine.expression(partition.getExpression(), slots, rows).getDependencies(), target,
                        write.getId(), "write.partition." + partition.getKind(), null, partition.getExpression().getSourceRef()));
            }
            for (UnknownMapping unknown : write.getUnknownMappings()) {
                dependencies.add(engine.support().dependency(new UnknownEndpoint(FactSupport.diagnostic(unknown.getDiagnosticCode(),
                                "Write field mapping has an unknown interval", null)), writeRows, DependencyRole.SCHEMA, DependencyPrecision.UNKNOWN,
                        write.getId(), "write.mapping.unknown", Collections.emptyList(), java.util.Optional.empty()));
            }
        }
        dependencies = FactSupport.distinct(dependencies);
        all.addAll(dependencies);
        diagnostics.addAll(FactSupport.diagnostics(all).getDiagnostics());
        return new LineageFacts(relations, dependencies, FactSupport.quality(all), new DiagnosticBag(diagnostics), engine.support().graph());
    }
}
