package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** A local extension rule; no parser, file access, provider or Session is consulted. */
public final class ExternalDatasetLineageRule implements RelationLineageRule {
    @Override
    public RelationFacts analyze(BoundRelation relation, List<RelationFacts> inputs,
            Map<NodeId, ExpressionFacts> expressions, LineageRuleContext context) {
        if (!(relation instanceof ExternalDatasetRelation) || !inputs.isEmpty()) {
            throw new IllegalArgumentException("External rule requires one leaf storage declaration");
        }
        ExternalDatasetRelation external = (ExternalDatasetRelation) relation;
        RowSetEndpoint target = new RowSetEndpoint(new RelationInstanceId("rowset:" + relation.getId().getValue()));
        Dependency storage = context.getEvidenceFactory().dependency(new ExternalDatasetEndpoint(external.getDatasetIdentity(), external.getSourceKind().name()),
            target, DependencyRole.STORAGE, DependencyPrecision.CONSERVATIVE, relation.getId(), "external.storage", Collections.emptyList(), relation.getSourceRef());
        DiagnosticBag diagnostics = new DiagnosticBag(Collections.singletonList(new Diagnostic(AnalysisStage.ANALYSIS,
            "EXTERNAL_SCHEMA_UNKNOWN", "Storage contents and ordered fields were not supplied", relation.getSourceRef().orElse(null))));
        Dependency unknown = context.getEvidenceFactory().dependency(new UnknownEndpoint(diagnostics), target, DependencyRole.SCHEMA, DependencyPrecision.UNKNOWN,
            relation.getId(), "external.schema", Collections.emptyList(), relation.getSourceRef());
        return new RelationFacts(relation.getId(), Collections.<SlotId, ExpressionFacts>emptyMap(), Arrays.asList(storage, unknown), diagnostics);
    }
}
