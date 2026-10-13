package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.analysis.ColumnEndpoint;
import com.panfan.hivelineage.v2.analysis.DefaultLineageEngine;
import com.panfan.hivelineage.v2.analysis.DirectFieldEndpoint;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.FieldId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.identity.SlotId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.OutputSlot;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.relation.SlotMapping;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundRelationReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class BoundNameIndexScopeTest {
    private static final FieldId SHARED_FIELD = new FieldId("opaque-field-7");
    private static final QueryBlockId BLOCK = new QueryBlockId("opaque-query-block");

    @Test
    public void verifiedOpaqueFieldsAreDisplayedWithinTheirPhysicalOwnersAndExportedWithoutCrossTalk() {
        Relations.Scan left = scan("left", "object-one", "alpha");
        Relations.Scan right = scan("right", "object-two", "beta");
        Relations.SetOp union = new Relations.SetOp(new NodeId("union"), schema("union-slot", "combined"), BLOCK, 1, null,
                Arrays.asList(left.getId(), right.getId()), Relations.SetOperator.UNION, Relations.DuplicatePolicy.ALL,
                Collections.singletonList(new Relations.SetOutputMapping(new SlotId("union-slot"), Arrays.asList(
                        Relations.BranchSlot.known(new SlotId("left-slot")), Relations.BranchSlot.known(new SlotId("right-slot"))))));
        BoundWrite write = write(union.getId(), "total", null,
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()),
                AnalysisFixtures.ref("write-ref", "union-slot"));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, union), Collections.singletonList(write));
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit,
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA, BindingRequirement.SINK_MAPPING));
        BoundNameIndex names = new BoundNameIndex(unit);
        Assert.assertEquals("db.object-one.alpha", names.column(endpoint("object-one"), false).get());
        Assert.assertEquals("db.object-two.beta", names.column(endpoint("object-two"), false).get());
        Assert.assertEquals(Collections.singletonList("alpha"), names.columnAddress(endpoint("object-one"), false).get().getFieldPath());
        Assert.assertFalse(names.declaredField(SHARED_FIELD).isPresent());
        ColumnLineageRecord record = new DefaultFormalLineageRecordExporter().exportColumns(statement(unit, facts)).get(0);
        Assert.assertEquals(Collections.singletonList("total"), record.getTarget().getFieldPath());
        Assert.assertEquals(Arrays.asList("db.object-one.alpha", "db.object-two.beta"), record.getSources().stream()
                .map(BoundNameIndexScopeTest::address).collect(Collectors.toList()));
    }

    @Test
    public void logicalOccurrencesUseTheirOwnOutputDeclarationsWhenNoPhysicalObjectExists() {
        Relations.Scan left = scan("left", "object-one", "alpha");
        Relations.Scan right = scan("right", "object-two", "beta");
        Relations.DefinitionInstance x = definition("x", left, "renamed_alpha", null);
        Relations.DefinitionInstance y = definition("y", right, "renamed_beta", null);
        BoundAnalysisUnit base = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, x, y), Collections.emptyList());
        Map<RelationInstanceId, BoundRelationReference> references = new LinkedHashMap<RelationInstanceId, BoundRelationReference>();
        references.put(x.getInstanceId(), reference(x, "x"));
        references.put(y.getInstanceId(), reference(y, "y"));
        BoundAnalysisUnit unit = copy(base, base.getObjects(), references);
        AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA);
        BoundNameIndex names = new BoundNameIndex(unit);
        DirectFieldEndpoint xField = new DirectFieldEndpoint(x.getInstanceId(), SHARED_FIELD, Collections.emptyList(), Optional.empty());
        DirectFieldEndpoint yField = new DirectFieldEndpoint(y.getInstanceId(), SHARED_FIELD, Collections.emptyList(), Optional.empty());
        Assert.assertEquals("x.renamed_alpha", names.column(xField, true).get());
        Assert.assertEquals("y.renamed_beta", names.column(yField, true).get());
        Assert.assertEquals(Collections.singletonList("renamed_alpha"), names.columnAddress(xField, true).get().getFieldPath());
        Assert.assertEquals(Collections.singletonList("renamed_beta"), names.columnAddress(yField, true).get().getFieldPath());
        Assert.assertFalse(names.column(endpoint("missing-object"), false).isPresent());
    }

    @Test
    public void definitionExportsResolveSourceFieldsWithinTheirObjectsRatherThanTheViewFieldIdentity() {
        Relations.Scan scan = scan("base", "base-object", "alpha");
        ObjectId viewObject = new ObjectId("view-object");
        Relations.DefinitionInstance view = definition("view", scan, "view_alpha", viewObject);
        BoundAnalysisUnit base = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(scan, view), Collections.emptyList());
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<ObjectId, BoundObjectReference>(base.getObjects());
        objects.put(viewObject, new BoundObjectReference(viewObject, new QualifiedName(Arrays.asList("db", "view")),
                BoundObjectReference.ObjectKind.VIEW, SchemaState.KNOWN, Collections.singletonList(new FieldIdentity(SHARED_FIELD, "view_alpha"))));
        BoundAnalysisUnit unit = copy(base, objects, Collections.emptyMap());
        LineageFacts facts = new DefaultLineageEngine().analyze(AnalysisFixtures.verify(unit,
                BindingRequirement.OBJECTS, BindingRequirement.OUTPUT_SCHEMA));
        ColumnLineageRecord record = new DefaultFormalLineageRecordExporter().exportColumns(statement(unit, facts)).get(0);
        Assert.assertEquals("db.view.view_alpha", address(record.getTarget()));
        Assert.assertEquals("db.base-object.alpha", address(record.getSources().get(0)));
    }

    @Test
    public void conditionAndPartitionHandlesRegisterOwnerScopedNamesEvenWithoutRequestedScanSchemas() {
        Relations.Scan left = emptyScan("left", "object-one");
        Relations.Scan right = emptyScan("right", "object-two");
        QualifiedFieldHandle condition = handle("condition", left.getInstanceId(), "predicate_source");
        QualifiedFieldHandle partition = handle("partition", right.getInstanceId(), "partition_source");
        Relations.Join input = new Relations.Join(new NodeId("write-input"), OutputSchema.notRequested(), BLOCK, 1, null,
                left.getId(), right.getId(), Relations.JoinType.CROSS, Relations.JoinForm.EXPLICIT, null,
                Collections.emptyList(), Collections.emptyList());
        PartitionScope scope = new PartitionScope(PartitionScope.Kind.DYNAMIC, Collections.singletonList(
                new PartitionScope.Assignment(new FieldIdentity("target-partition", "partition_day"),
                        PartitionScope.AssignmentKind.DYNAMIC, partition)));
        BoundWrite write = write(input.getId(), "result", condition, scope, AnalysisFixtures.literal("literal", "fixed"));
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right, input), Collections.singletonList(write));
        AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS);
        BoundNameIndex names = new BoundNameIndex(unit);
        Assert.assertEquals("db.object-one.predicate_source", names.column(endpoint("object-one"), false).get());
        Assert.assertEquals("db.object-two.partition_source", names.column(endpoint("object-two"), false).get());
    }

    private static String address(ColumnAddress address) {
        return address.getObject().getDisplayName() + "." + String.join(".", address.getFieldPath());
    }

    private static ColumnEndpoint endpoint(String object) {
        return new ColumnEndpoint(new ObjectId(object), SHARED_FIELD, Collections.emptyList());
    }

    private static OutputSchema schema(String slot, String display) {
        return OutputSchema.known(Collections.singletonList(new OutputSlot(new SlotId(slot), display, TypeRef.unknown(),
                new FieldIdentity(SHARED_FIELD, display))));
    }

    private static Relations.Scan scan(String id, String object, String display) {
        return new Relations.Scan(new NodeId(id), schema(id + "-slot", display), BLOCK, 0, null,
                new ObjectId(object), VersionRef.notRequested(), new RelationInstanceId(id + "-instance"));
    }

    private static Relations.Scan emptyScan(String id, String object) {
        return new Relations.Scan(new NodeId(id), OutputSchema.notRequested(), BLOCK, 0, null,
                new ObjectId(object), VersionRef.notRequested(), new RelationInstanceId(id + "-instance"));
    }

    private static Relations.DefinitionInstance definition(String id, Relations.Scan input, String display, ObjectId object) {
        OutputSchema output = object == null ? OutputSchema.known(Collections.singletonList(new OutputSlot(
                new SlotId(id + "-slot"), display, TypeRef.unknown(), new FieldIdentity(SHARED_FIELD, "other-label-" + id))))
                : schema(id + "-slot", display);
        return new Relations.DefinitionInstance(new NodeId(id), output, BLOCK, 1, null,
                input.getId(), new DefinitionId(id + "-definition"), new RelationInstanceId(id + "-instance"),
                VersionRef.notRequested(), Collections.singletonList(new SlotMapping(new SlotId(id + "-slot"),
                        new SlotId(input.getId().getValue() + "-slot"))), object);
    }

    private static BoundRelationReference reference(Relations.DefinitionInstance relation, String alias) {
        return new BoundRelationReference(relation.getInstanceId(), new QualifiedName(Collections.singletonList(alias)),
                Optional.empty(), Optional.of(relation.getDefinitionId()), BLOCK);
    }

    private static QualifiedFieldHandle handle(String id, RelationInstanceId owner, String display) {
        return new QualifiedFieldHandle(new NodeId(id), TypeRef.unknown(), null, owner, new FieldIdentity(SHARED_FIELD, display),
                Collections.emptyList(), QualifiedFieldHandle.ExistenceState.UNVERIFIED);
    }

    private static BoundWrite write(NodeId source, String display, QualifiedFieldHandle condition, PartitionScope partition,
                                   com.panfan.hivelineage.v2.ir.expression.BoundExpression expression) {
        return new BoundWrite(new NodeId("write"), new ObjectId("target-object"), VersionRef.notRequested(), VersionRef.notRequested(),
                BoundWrite.WriteMode.APPEND, Collections.singletonList(new WriteAssignment(new FieldIdentity(SHARED_FIELD, display), expression)),
                partition, condition, MappingCoverage.COMPLETE, Collections.emptyList(), source);
    }

    private static BoundAnalysisUnit copy(BoundAnalysisUnit base, Map<ObjectId, BoundObjectReference> objects,
                                         Map<RelationInstanceId, BoundRelationReference> references) {
        return new BoundAnalysisUnit(base.getStatementId(), base.getRelations(), base.getQueryResults(), base.getWrites(), base.getEffects(),
                base.getBindingCoverage(), base.getDiagnostics(), base.getNotRequestedRegions(), base.getExecutionAssumption(),
                objects, base.getScopes(), references);
    }

    private static StatementAnalysisResult statement(BoundAnalysisUnit unit, LineageFacts facts) {
        TaskIdentity task = new TaskIdentity("workflow", "task", "version");
        SourceDocument source = new SourceDocument("no-sql", "");
        return new StatementAnalysisResult(new StatementIdentity(task, 0, "statement-identity"), "INSERT_INTO", source, source,
                StatementStatus.SUCCESS, true, Optional.of(facts), Optional.empty(), Optional.of(unit), Optional.empty(),
                Optional.empty(), Optional.empty(), DiagnosticBag.empty());
    }
}
