package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project combines pre-analyzed expressions with an independently preserved input rowset. */
public final class CompositionalProjectRule implements RelationLineageRule {
    @Override
    public RelationFacts analyze(BoundRelation relation, List<RelationFacts> inputs, Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context) {
        if (!(relation instanceof Relations.Project)) {
            throw new IllegalArgumentException("Project rule requires a Project node");
        }
        EvidenceFactory support = context.getEvidenceFactory();
        Map<SlotId, ExpressionFacts> output = new LinkedHashMap<SlotId, ExpressionFacts>();
        for (OutputAssignment assignment : ((Relations.Project) relation).getAssignments()) {
            ExpressionFacts expression = expressions.get(assignment.getExpression().getId());
            output.put(assignment.getOutput(), RelationFactsEngine.facts(assignment.getExpression().getId(), support.retarget(
                    expression.getDependencies(), new SlotEndpoint(assignment.getOutput()), relation.getId(), "project", null, relation.getSourceRef())));
        }
        List<Dependency> rows = new ArrayList<Dependency>();
        RowSetEndpoint target = new RowSetEndpoint(new RelationInstanceId("rowset:" + relation.getId().getValue()));
        for (RelationFacts input : inputs) {
            rows.addAll(support.retarget(input.getRowSetFacts(), target, relation.getId(), "project.rows", null, relation.getSourceRef()));
        }
        if (RelationFactsEngine.hasUnknownWidth(relation)) {
            rows.add(support.dependency(new UnknownEndpoint(FactSupport.diagnostic("OUTPUT_SCHEMA_UNKNOWN", "Projection width is unknown", null)),
                    target, DependencyRole.SCHEMA, DependencyPrecision.UNKNOWN, relation.getId(), "schema.unknown", Collections.emptyList(), relation.getSourceRef()));
        }
        return new RelationFacts(relation.getId(), output, FactSupport.distinct(rows), FactSupport.diagnostics(rows));
    }
}
