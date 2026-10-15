package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.analysis.DependencyPrecision;
import com.panfan.hivelineage.v2.analysis.DependencyRole;
import com.panfan.hivelineage.v2.analysis.EvidenceFactory;
import com.panfan.hivelineage.v2.analysis.LineageRuleContext;
import com.panfan.hivelineage.v2.analysis.SlotEndpoint;
import com.panfan.hivelineage.v2.analysis.SlotProvenanceIndex;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.export.BoundExpressionFormatter;
import com.panfan.hivelineage.v2.export.BoundNameIndex;
import com.panfan.hivelineage.v2.ir.expression.BoundExpression;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.expression.FunctionSymbol;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.relation.OutputAssignment;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class SharedSlotProvenanceTest {
    @Test
    public void formatterAndBoundarySelectionShareWindowFrameProvenance() {
        Relations.Scan scan = AnalysisFixtures.scan("scan", "base", "main", "value", "offset");
        ObjectId viewId = new ObjectId("view-object");
        Relations.DefinitionInstance view = new Relations.DefinitionInstance(new NodeId("view"),
                OutputSchema.known(Arrays.asList(AnalysisFixtures.output("view.value"), AnalysisFixtures.output("view.offset"))),
                new QueryBlockId("main"), 1, null, scan.getId(), new DefinitionId("view-definition"), new RelationInstanceId("view-instance"),
                VersionRef.notRequested(), Arrays.asList(new SlotMapping(new SlotId("view.value"), new SlotId("scan.value")),
                new SlotMapping(new SlotId("view.offset"), new SlotId("scan.offset"))), viewId);
        BoundExpression offset = AnalysisFixtures.call("offset-function", "abs", FunctionSymbol.FunctionKind.SCALAR,
                AnalysisFixtures.ref("frame-offset", "view.offset"));
        Relations.WindowComputation computation = new Relations.WindowComputation(new OutputAssignment(new SlotId("window.result"),
                AnalysisFixtures.call("sum", "sum", FunctionSymbol.FunctionKind.WINDOW, AnalysisFixtures.ref("value", "view.value"))),
                Collections.emptyList(), Collections.emptyList(), new Relations.WindowFrame(Relations.FrameUnit.ROWS,
                new Relations.FrameBound(Relations.FrameBoundKind.PRECEDING, offset),
                new Relations.FrameBound(Relations.FrameBoundKind.CURRENT_ROW, null)));
        Relations.Window window = new Relations.Window(new NodeId("window"), OutputSchema.known(Collections.singletonList(AnalysisFixtures.output("window.result"))),
                new QueryBlockId("main"), 2, null, view.getId(), Collections.emptyList(), Collections.singletonList(computation));
        BoundAnalysisUnit base = AnalysisFixtures.unit(Arrays.asList(scan, view, window), Collections.emptyList());
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<ObjectId, BoundObjectReference>(base.getObjects());
        objects.put(viewId, new BoundObjectReference(viewId, new QualifiedName(Arrays.asList("db", "view")), BoundObjectReference.ObjectKind.VIEW));
        BoundAnalysisUnit unit = new BoundAnalysisUnit(base.getStatementId(), base.getRelations(), base.getQueryResults(), base.getWrites(), base.getEffects(),
                base.getBindingCoverage(), base.getDiagnostics(), base.getNotRequestedRegions(), base.getExecutionAssumption(), objects, base.getScopes());
        BoundNameIndex names = new BoundNameIndex(unit);
        SlotProvenanceIndex provenance = new SlotProvenanceIndex(unit);
        Assert.assertEquals(Collections.singletonList(offset), provenance.extra().get(new SlotId("window.result")));
        Expressions.SlotRef result = AnalysisFixtures.ref("result", "window.result");
        BoundExpressionFormatter formatter = new BoundExpressionFormatter(provenance, names, source -> Optional.empty(), names::view);
        Assert.assertEquals(Arrays.asList("sum", "abs"), formatter.functions(result));
        EvidenceFactory evidence = new LineageRuleContext("1", Collections.emptyMap()).getEvidenceFactory();
        List<Dependency> dependencies = Arrays.asList(dependency(evidence, viewId, "view.value"), dependency(evidence, viewId, "view.offset"),
                dependency(evidence, new ObjectId("base"), "base.offset"));
        LegacyColumnSourceSelector selector = new LegacyColumnSourceSelector(unit, names, false, provenance);
        Assert.assertEquals(Arrays.asList("view.value", "view.offset"), selector.select(dependencies, result, window.getId()).stream()
                .map(dependency -> ((ColumnEndpoint) dependency.getSource()).getFieldId().getValue()).collect(Collectors.toList()));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void provenanceAssignmentsCannotBeMutatedByConsumers() {
        SlotProvenanceIndex provenance = new SlotProvenanceIndex(AnalysisFixtures.unit(Collections.emptyList(), Collections.emptyList()));
        provenance.assignments().put(new SlotId("illegal"), AnalysisFixtures.literal("literal", "1"));
    }

    private static Dependency dependency(EvidenceFactory support, ObjectId object, String field) {
        return support.dependency(new ColumnEndpoint(object, new FieldId(field), Collections.emptyList()),
                new SlotEndpoint(new SlotId("window.result")), DependencyRole.CONDITION, DependencyPrecision.STRUCTURAL,
                new NodeId("window"), "window.frame", Collections.emptyList(), Optional.empty());
    }
}
