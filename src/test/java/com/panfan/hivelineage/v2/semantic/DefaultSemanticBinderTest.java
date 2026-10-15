package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.AnalysisCapabilities;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.DefinitionPolicy;
import com.panfan.hivelineage.v2.api.DialectSyntaxFrontend;
import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.api.StatementPublicationPolicy;
import com.panfan.hivelineage.v2.api.VariablePolicy;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.ScriptUnitKind;
import com.panfan.hivelineage.v2.frontend.SourceMap;
import com.panfan.hivelineage.v2.frontend.SourceMapEntry;
import com.panfan.hivelineage.v2.frontend.VariableEvidence;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.CorrelatedQualifiedFieldHandle;
import com.panfan.hivelineage.v2.ir.expression.Expressions;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.relation.KnownOutputSegment;
import com.panfan.hivelineage.v2.ir.relation.ExternalDatasetRelation;
import com.panfan.hivelineage.v2.metadata.ColumnMetadata;
import com.panfan.hivelineage.v2.metadata.DefinitionContext;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataAccess;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationMetadata;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.session.InitialSessionContext;
import com.panfan.hivelineage.v2.session.SessionSnapshot;
import com.panfan.hivelineage.v2.session.VariableKey;
import com.panfan.hivelineage.v2.session.VariableState;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class DefaultSemanticBinderTest {
    @Test
    public void structuredMultiPartDefaultDatabaseCannotBecomeAPhysicalNamespace() {
        AnalysisContract contract = contract(false, AnalysisCapability.TABLE_LINEAGE);
        TestMetadata metadata = new TestMetadata();
        InitialSessionContext initial = new InitialSessionContext("session", "wf", "task", "V1", "cluster",
                Optional.of(name("db", "foo")), Collections.<VariableKey, VariableState>emptyMap(),
                Collections.<String, String>emptyMap());
        BindingContext context = new BindingContext(SessionSnapshot.initial(initial), metadata, readContext(),
                Collections.<DefinitionId, PreparedDefinition>emptyMap(), new DefaultFunctionCatalog(),
                new HiveDialectFactory().getProfile(), contract, Collections.<DefinitionId, DiagnosticBag>emptyMap());
        SyntaxStatement unqualified = parse("insert into dwd.t select id from a");
        BindingResult invalid = new DefaultSemanticBinder().bind(unqualified, context,
                new DefaultAnalysisDemandPlanner().plan(unqualified, contract));
        assertEquals(BindingOutcome.FAILED, invalid.getOutcome());
        assertTrue(invalid.getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> "INVALID_DATABASE_NAME".equals(diagnostic.getCode())));
        assertFalse(invalid.getUnit().get().getObjects().values().stream()
                .anyMatch(object -> object.getName().getParts().size() > 2));
        SyntaxStatement qualified = parse("insert into dwd.t select id from ods.a");
        BindingResult valid = new DefaultSemanticBinder().bind(qualified, context,
                new DefaultAnalysisDemandPlanner().plan(qualified, contract));
        assertEquals(BindingOutcome.COMPLETE, valid.getOutcome());
        assertEquals(0, metadata.schemaReads);
    }

    @Test
    public void requestedDatasetVersionsDeclarePhysicalScriptEntryAndLogicalUnknowns() {
        BindingResult result = bind("with x as(select id from ods.a) insert into dwd.t(id) select id from x",
                new TestMetadata(), AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION);
        Relations.Scan scan = (Relations.Scan) result.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.Scan).findFirst().get();
        assertEquals(VersionRef.State.KNOWN, scan.getVersion().getState());
        assertTrue(scan.getVersion().getId().get().getValue().endsWith("/script-entry"));
        Relations.DefinitionInstance definition = (Relations.DefinitionInstance) result.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.DefinitionInstance).findFirst().get();
        assertEquals(VersionRef.State.UNKNOWN, definition.getVersion().getState());
        BindingResult withoutVersionDemand = bind("insert into dwd.t(id) select id from ods.a",
                new TestMetadata(), AnalysisCapability.COLUMN_LINEAGE);
        Relations.Scan unrequested = (Relations.Scan) withoutVersionDemand.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.Scan).findFirst().get();
        assertEquals(VersionRef.State.NOT_REQUESTED, unrequested.getVersion().getState());
        String insert = "insert into dwd.t(id) select id from ods.a";
        BindingResult first = bindInSession(insert, new TestMetadata(), "session-a",
                AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION);
        BindingResult second = bindInSession(insert, new TestMetadata(), "session-b",
                AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION);
        Relations.Scan firstScan = (Relations.Scan) first.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.Scan).findFirst().get();
        Relations.Scan secondScan = (Relations.Scan) second.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.Scan).findFirst().get();
        assertEquals(firstScan.getObjectId(), secondScan.getObjectId());
        assertNotEquals(firstScan.getVersion().getId(), secondScan.getVersion().getId());
        assertNotEquals(first.getUnit().get().getWrites().get(0).getBeforeVersion().getId(),
                second.getUnit().get().getWrites().get(0).getBeforeVersion().getId());
        assertNotEquals(first.getUnit().get().getWrites().get(0).getAfterVersion().getId(),
                second.getUnit().get().getWrites().get(0).getAfterVersion().getId());
        BindingResult firstUpdate = bindInSession("update dwd.t set id=id+1", new TestMetadata(), "session-a",
                AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION);
        BindingResult secondUpdate = bindInSession("update dwd.t set id=id+1", new TestMetadata(), "session-b",
                AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION);
        assertNotEquals(firstUpdate.getUnit().get().getWrites().get(0).getBeforeVersion().getId(),
                secondUpdate.getUnit().get().getWrites().get(0).getBeforeVersion().getId());
        assertNotEquals(firstUpdate.getUnit().get().getWrites().get(0).getAfterVersion().getId(),
                secondUpdate.getUnit().get().getWrites().get(0).getAfterVersion().getId());
    }

    @Test
    public void loadRequiresExplicitCapabilityAndDoesNotInventExternalFileColumns() {
        String sql = "load data local inpath '/tmp/input/data.tsv' overwrite into table dwd.t partition(dt='20261012')";
        BindingResult legacy = bind(sql, new TestMetadata(), AnalysisCapability.TABLE_LINEAGE);
        assertEquals(BindingOutcome.UNSUPPORTED, legacy.getOutcome());
        assertTrue(legacy.getUnit().get().getWrites().isEmpty());
        BindingResult enabled = bind(sql, new TestMetadata().table("dwd.t", "id", "dt"),
                AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.EXTERNAL_DATA_LINEAGE, AnalysisCapability.DATASET_VERSION_SIMULATION);
        assertEquals(BindingOutcome.PARTIAL, enabled.getOutcome());
        ExternalDatasetRelation input = (ExternalDatasetRelation) enabled.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof ExternalDatasetRelation).findFirst().get();
        assertEquals("/tmp/input/data.tsv", input.getDatasetIdentity());
        assertEquals(ExternalDatasetRelation.SourceKind.LOCAL_PATH, input.getSourceKind());
        assertEquals(SchemaState.UNKNOWN, input.getOutputSchema().getState());
        BoundWrite write = enabled.getUnit().get().getWrites().get(0);
        assertEquals(BoundWrite.WriteMode.OVERWRITE, write.getWriteMode());
        assertEquals(input.getId(), write.getSourceRelation().get());
        assertEquals(2, write.getAssignments().size());
        assertTrue(write.getAssignments().get(0).getExpression() instanceof Expressions.UnknownExpression);
        assertEquals("dt", write.getAssignments().get(1).getTargetField().getDisplayName());
        assertTrue(write.getAssignments().get(1).getExpression() instanceof Expressions.Literal);
        assertFalse(write.getUnknownMappings().isEmpty());
    }

    @Test
    public void partialSetRetainsKnownBranchesAndExplicitUnknownWidth() {
        TestMetadata metadata = new TestMetadata().table("ods.a", "id").table("dwd.t", "id");
        BindingResult result = bind("insert into dwd.t(id) select id from ods.a union all select * from ods.missing",
                metadata, AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(BindingOutcome.PARTIAL, result.getOutcome());
        Relations.SetOp set = (Relations.SetOp) result.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.SetOp).findFirst().get();
        assertEquals(SchemaState.UNKNOWN, set.getOutputSchema().getState());
        assertTrue(set.getOutputSchema().getSegments().get(0) instanceof KnownOutputSegment);
        assertEquals(1, set.getMappings().size());
        assertTrue(set.getMappings().get(0).getBranchSlots().get(0).getSlot().isPresent());
        assertFalse(set.getMappings().get(0).getBranchSlots().get(1).getSlot().isPresent());
        assertFalse(result.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "COLUMN_COUNT_MISMATCH".equals(diagnostic.getCode())));
    }

    @Test
    public void existingConditionalCtasDoesNotBindAReplacementQueryOrWrite() {
        TestMetadata metadata = new TestMetadata();
        metadata.views.put("dwd.t", new ObjectDescriptor(new ObjectId("existing-target"), name("dwd", "t"),
                ObjectDescriptor.Kind.TABLE, Optional.<DefinitionRef>empty(), provenance(), MetadataVersion.unknown()));
        BindingResult result = bind("create table if not exists dwd.t as select id from ods.a", metadata,
                AnalysisCapability.TABLE_LINEAGE, AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.CATALOG_SIMULATION);
        assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
        assertTrue(result.getUnit().get().getWrites().isEmpty());
        assertTrue(result.getUnit().get().getEffects().isEmpty());
        assertEquals(0, metadata.schemaReads);
    }

    @Test
    public void unknownConditionalCtasKeepsCreateAndDefinitionPreconditionsConsistent() {
        BindingResult result = bind("create table if not exists dwd.t as select 1 as id", new TestMetadata(),
                AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.CATALOG_SIMULATION, AnalysisCapability.DATASET_VERSION_SIMULATION);
        assertEquals(2, result.getUnit().get().getEffects().size());
        for (com.panfan.hivelineage.v2.ir.statement.BoundEffect effect : result.getUnit().get().getEffects()) {
            assertEquals(Effects.Certainty.CONDITIONAL, effect.getCertainty());
            assertEquals(Effects.Precondition.Kind.OBJECT_ABSENT, effect.getPrecondition().getKind());
        }
        assertEquals(1, result.getUnit().get().getWrites().size());
    }

    @Test
    public void constantQueryHasKnownValuesRowsWithoutInventedUnknownRelation() {
        BindingResult result = bind("create table dwd.t as select 1 as id", new TestMetadata(), AnalysisCapability.COLUMN_LINEAGE);
        assertTrue(result.getUnit().get().getRelations().stream().anyMatch(relation -> relation instanceof Relations.Values));
        assertFalse(result.getUnit().get().getRelations().stream().anyMatch(relation -> relation instanceof Relations.UnknownRelation));
        assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
    }

    @Test
    public void unresolvedOwnerAndUnsupportedFourPartNameNeverBindTheOnlyPhysicalTable() {
        BindingResult result = bind("insert into dwd.t(id,name) select missing.id,catalog.db.a.name from ods.a a",
                new TestMetadata(), AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(BindingOutcome.PARTIAL, result.getOutcome());
        assertTrue(result.getUnit().get().getObjects().values().stream().filter(object -> object.getName().getDisplayName().equals("ods.a"))
                .allMatch(object -> object.getFields().isEmpty()));
        assertEquals(2, result.getDiagnostics().getDiagnostics().stream().filter(diagnostic -> "UNKNOWN_QUALIFIED_OWNER".equals(diagnostic.getCode())).count());
    }

    @Test
    public void tableOnlyDoesNotReadSchemaEvenForAmbiguousFieldsStarsAndCorrelatedQueries() {
        TestMetadata metadata = new TestMetadata();
        BoundAnalysisUnit unit = bind("insert into dwd.t select * from ods.a a join ods.b b on id=b.id where exists(select * from ods.c c where c.id=a.id)",
                metadata, AnalysisCapability.TABLE_LINEAGE).getUnit().get();
        assertEquals(0, metadata.schemaReads);
        assertEquals(0, metadata.objectReads);
        assertEquals(1, unit.getWrites().size());
        assertEquals(3L, unit.getRelations().stream().filter(relation -> relation instanceof Relations.Scan).count());
        assertTrue(unit.getDiagnostics().getDiagnostics().isEmpty());
    }

    @Test
    public void joinOnlyDoesNotExpandUnrelatedProjectionOrLoadQualifiedPhysicalFields() {
        TestMetadata metadata = new TestMetadata();
        BindingResult result = bind("select * from ods.a a left join ods.b b on a.id=b.id and b.dt='20261012'", metadata,
                AnalysisCapability.JOIN_CONDITION);
        assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
        assertEquals(0, metadata.schemaReads);
        assertEquals(0, metadata.objectReads);
        assertTrue(result.getUnit().get().getWrites().isEmpty());
        Relations.Join join = (Relations.Join) result.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.Join).findFirst().get();
        assertEquals(Relations.JoinType.LEFT, join.getJoinType());
        assertEquals(2, join.getPredicates().get(0).getOwners().size());
        assertEquals(VerificationOutcome.ACCEPTED, new DefaultIrVerifier().verify(result.getUnit().get(),
                demand(parse("select * from ods.a a left join ods.b b on a.id=b.id and b.dt='20261012'"), AnalysisCapability.JOIN_CONDITION)).getOutcome());
    }

    @Test
    public void positionalStarPreservesDifferentSourceAndTargetNames() {
        TestMetadata metadata = new TestMetadata().table("ods.a", "id", "sex").table("dwd.t", "user_id", "gender");
        BindingResult result = bind("insert into dwd.t select * from ods.a", metadata, AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
        BoundWrite write = result.getUnit().get().getWrites().get(0);
        assertEquals(2, write.getAssignments().size());
        assertEquals("user_id", write.getAssignments().get(0).getTargetField().getDisplayName());
        assertEquals("gender", write.getAssignments().get(1).getTargetField().getDisplayName());
        assertTrue(write.getAssignments().get(1).getExpression() instanceof Expressions.SlotRef);
        assertEquals(2, metadata.schemaReads);
    }

    @Test
    public void missingStarSchemaDoesNotBecomeKnownWidthOrCountMismatch() {
        BindingResult result = bind("insert into dwd.t select * from ods.missing", new TestMetadata().table("dwd.t", "id"),
                AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(BindingOutcome.PARTIAL, result.getOutcome());
        assertTrue(result.getUnit().get().getWrites().get(0).getAssignments().stream()
                .allMatch(assignment -> assignment.getExpression() instanceof Expressions.UnknownExpression));
        assertFalse(result.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "COLUMN_COUNT_MISMATCH".equals(diagnostic.getCode())));
    }

    @Test
    public void cteDerivedTablesSelfJoinsAndUnionBranchesHaveSeparateIdentities() {
        TestMetadata metadata = new TestMetadata().table("dwd.t", "id");
        BindingResult result = bind("with x as(select id from ods.a) insert into dwd.t select a.id from x a join x b on a.id=b.id union all select a.id from ods.c a",
                metadata, AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.JOIN_CONDITION);
        assertNotEquals(BindingOutcome.FAILED, result.getOutcome());
        List<Relations.DefinitionInstance> instances = new ArrayList<>();
        for (BoundRelation relation : result.getUnit().get().getRelations()) {
            if (relation instanceof Relations.DefinitionInstance) {
                instances.add((Relations.DefinitionInstance) relation);
            }
        }
        List<Relations.DefinitionInstance> sameCte = new ArrayList<>();
        for (Relations.DefinitionInstance instance : instances) {
            if (instance.getDefinitionId().equals(instances.get(0).getDefinitionId())) {
                sameCte.add(instance);
            }
        }
        assertEquals(2, sameCte.size());
        assertNotEquals(sameCte.get(0).getInstanceId(), sameCte.get(1).getInstanceId());
        assertNotEquals(sameCte.get(0).getOutputSchema(), sameCte.get(1).getOutputSchema());
        assertEquals(1, result.getUnit().get().getWrites().get(0).getAssignments().size());
    }

    @Test
    public void windowAndExplodeExposeSeparateSemanticOperators() {
        TestMetadata metadata = new TestMetadata().table("dwd.t", "id", "rn", "tag");
        BindingResult result = bind("insert into dwd.t select a.id,row_number() over(partition by a.id order by a.dt),v.tag from ods.a a lateral view explode(a.tags) v as tag",
                metadata, AnalysisCapability.COLUMN_LINEAGE);
        assertNotEquals(BindingOutcome.FAILED, result.getOutcome());
        assertTrue(result.getUnit().get().getRelations().stream().anyMatch(relation -> relation instanceof Relations.Generate));
        Relations.Window window = (Relations.Window) result.getUnit().get().getRelations().stream()
                .filter(relation -> relation instanceof Relations.Window).findFirst().get();
        assertEquals(1, window.getComputations().size());
        assertEquals(1, window.getComputations().get(0).getPartitions().size());
        assertEquals(1, window.getComputations().get(0).getOrderKeys().size());
        assertEquals(3, result.getUnit().get().getWrites().get(0).getAssignments().size());
    }

    @Test
    public void correlatedQualifiedHandleDeclaresOuterScopeWithoutLoadingSchema() {
        TestMetadata metadata = new TestMetadata();
        BindingResult result = bind("select * from ods.a a join ods.b b on a.id=b.id where exists(select 1 from ods.c c where c.id=a.id)",
                metadata, AnalysisCapability.JOIN_CONDITION);
        boolean correlated = false;
        for (BoundRelation relation : result.getUnit().get().getRelations()) {
            if (relation instanceof Relations.Filter && ((Relations.Filter) relation).getPredicate() instanceof Expressions.Call) {
                Expressions.Call predicate = (Expressions.Call) ((Relations.Filter) relation).getPredicate();
                correlated |= predicate.getArguments().stream().anyMatch(argument -> argument instanceof CorrelatedQualifiedFieldHandle);
            }
        }
        assertTrue(correlated);
        assertEquals(0, metadata.schemaReads);
    }

    @Test
    public void staticAndDynamicPartitionsMapSeparately() {
        TestMetadata metadata = new TestMetadata().table("dwd.t", "id", "system", "dt");
        BindingResult result = bind("insert overwrite table dwd.t partition(system='profile',dt) select id,dt from ods.a",
                metadata, AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
        BoundWrite write = result.getUnit().get().getWrites().get(0);
        assertEquals(3, write.getAssignments().size());
        assertEquals("id", write.getAssignments().get(0).getTargetField().getDisplayName());
        assertEquals("dt", write.getAssignments().get(1).getTargetField().getDisplayName());
        assertEquals("system", write.getAssignments().get(2).getTargetField().getDisplayName());
        assertTrue(write.getAssignments().get(2).getExpression() instanceof Expressions.Literal);
    }

    @Test
    public void externalDefinitionRequestsStayOutsideTheBinderAndFailuresAreNotRetried() {
        TestMetadata metadata = new TestMetadata();
        MetadataReadContext read = readContext();
        DefinitionRef reference = new DefinitionRef(new DefinitionId("view-definition"), Optional.of("select id from ods.a"),
                new DefinitionContext(Optional.of(name("default")), Collections.<String, String>emptyMap(), "hive", "4.0.1", "4.0.1", read),
                provenance(), MetadataVersion.unknown(), SourceRef.synthetic("view"));
        metadata.views.put("dwd.v", new ObjectDescriptor(new ObjectId("view"), name("dwd", "v"),
                ObjectDescriptor.Kind.VIEW, Optional.of(reference), provenance(), MetadataVersion.unknown()));
        SyntaxStatement syntax = parse("insert into dwd.t(id) select id from dwd.v");
        AnalysisContract contract = contract(true, AnalysisCapability.COLUMN_LINEAGE);
        BindingContext context = context(metadata, contract, Collections.<DefinitionId, DiagnosticBag>emptyMap());
        BindingResult initial = new DefaultSemanticBinder().bind(syntax, context, new DefaultAnalysisDemandPlanner().plan(syntax, contract));
        assertEquals(BindingOutcome.NEEDS_DEFINITION, initial.getOutcome());
        assertEquals(1, initial.getDefinitionRequests().size());
        assertTrue(initial.getDefinitionRequests().get(0).getExpansionPath().isEmpty());
        BindingContext failed = context(metadata, contract, Collections.singletonMap(reference.getDefinitionId(), DiagnosticBag.empty()));
        BindingResult degraded = new DefaultSemanticBinder().bind(syntax, failed, new DefaultAnalysisDemandPlanner().plan(syntax, contract));
        assertEquals(BindingOutcome.PARTIAL, degraded.getOutcome());
        assertTrue(degraded.getDefinitionRequests().isEmpty());
        DefinitionRef opaque = new DefinitionRef(new DefinitionId("remote-view-definition"), Optional.<String>empty(),
                reference.getDefinitionContext(), provenance(), MetadataVersion.unknown(),
                SourceRef.synthetic("remote-view-reference"));
        metadata.views.put("dwd.v", new ObjectDescriptor(new ObjectId("view"), name("dwd", "v"),
                ObjectDescriptor.Kind.VIEW, Optional.of(opaque), provenance(), MetadataVersion.unknown()));
        BindingResult remote = new DefaultSemanticBinder().bind(syntax, context,
                new DefaultAnalysisDemandPlanner().plan(syntax, contract));
        assertEquals(BindingOutcome.NEEDS_DEFINITION, remote.getOutcome());
        assertEquals(1, remote.getDefinitionRequests().size());
        assertEquals(opaque, remote.getDefinitionRequests().get(0).getReference());
        assertFalse(remote.getDefinitionRequests().get(0).getReference().getDefinitionText().isPresent());
        assertTrue(remote.getDefinitionRequests().get(0).getExpansionPath().isEmpty());
        assertFalse(remote.getDiagnostics().getDiagnostics().stream()
                .anyMatch(diagnostic -> "VIEW_DEFINITION_NOT_FOUND".equals(diagnostic.getCode())));
        assertFalse(remote.getUnit().get().getObjects().values().stream()
                .anyMatch(object -> object.getName().equals(name("ods", "a"))));
    }

    @Test
    public void updateDeleteMergeAndMultipleInsertProduceIndividualContributions() {
        BindingResult update = bind("update dwd.t set score=score+1 where id=10", new TestMetadata(), AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(BoundWrite.WriteMode.UPDATE, update.getUnit().get().getWrites().get(0).getWriteMode());
        assertEquals(1, update.getUnit().get().getWrites().get(0).getAssignments().size());
        BindingResult delete = bind("delete from dwd.t where id=10", new TestMetadata(), AnalysisCapability.TABLE_LINEAGE);
        assertEquals(BoundWrite.WriteMode.DELETE, delete.getUnit().get().getWrites().get(0).getWriteMode());
        TestMetadata metadata = new TestMetadata().table("dwd.t", "id").table("dwd.u", "id");
        BindingResult multi = bind("from ods.a a insert into dwd.t select a.id insert into dwd.u select a.id", metadata,
                AnalysisCapability.COLUMN_LINEAGE);
        assertEquals(2, multi.getUnit().get().getWrites().size());
        assertNotEquals(multi.getUnit().get().getWrites().get(0).getTargetObject(), multi.getUnit().get().getWrites().get(1).getTargetObject());
    }

    @Test
    public void mergeKeepsEveryMatchedAndUnmatchedActionAsASeparateContribution() {
        BindingResult result = bind("merge into dwd.t t using ods.a a on t.id=a.id "
                        + "when matched and a.score>0 then update set score=a.score "
                        + "when matched then delete when not matched then insert values(a.id,a.score)",
                new TestMetadata().table("dwd.t", "id", "score"), AnalysisCapability.COLUMN_LINEAGE,
                AnalysisCapability.DATASET_VERSION_SIMULATION);
        assertNotEquals(BindingOutcome.FAILED, result.getOutcome());
        List<BoundWrite> writes = result.getUnit().get().getWrites();
        assertEquals(3, writes.size());
        assertEquals(BoundWrite.WriteMode.UPDATE, writes.get(0).getWriteMode());
        assertEquals(BoundWrite.WriteMode.DELETE, writes.get(1).getWriteMode());
        assertEquals(BoundWrite.WriteMode.APPEND, writes.get(2).getWriteMode());
        assertNotEquals(writes.get(0).getId(), writes.get(1).getId());
        assertNotEquals(writes.get(1).getId(), writes.get(2).getId());
        assertTrue(writes.stream().allMatch(write -> write.getCondition().isPresent()));
        assertTrue(writes.get(1).getAssignments().isEmpty());
        assertEquals(2, writes.get(2).getAssignments().size());
    }

    @Test
    public void caseElseOwnerProducesAnImplicitJoinFact() {
        String sql = "select a.id from db.a a, db.b b where case when a.flag=1 then a.id else b.id end > 0";
        SyntaxStatement syntax = parse(sql);
        AnalysisContract contract = contract(false, AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.JOIN_CONDITION);
        BindingDemand demand = new DefaultAnalysisDemandPlanner().plan(syntax, contract);
        BindingResult result = new DefaultSemanticBinder().bind(syntax,
                context(new TestMetadata().table("db.a", "id", "flag").table("db.b", "id"), contract,
                        Collections.<DefinitionId, DiagnosticBag>emptyMap()), demand);
        VerificationResult verified = new DefaultIrVerifier().verify(result.getUnit().get(), demand);
        assertEquals(VerificationOutcome.ACCEPTED, verified.getOutcome());
        com.panfan.hivelineage.v2.analysis.JoinFacts facts = new com.panfan.hivelineage.v2.analysis.DefaultJoinConditionAnalysis()
                .analyze(verified.getVerifiedUnit().get());
        assertEquals(1, facts.getJoins().size());
        assertEquals(com.panfan.hivelineage.v2.analysis.JoinKind.IMPLICIT, facts.getJoins().get(0).getKind());
        assertEquals(3, facts.getJoins().get(0).getPhysicalReferences().size());
    }

    @Test
    public void unknownExpressionRetainsFieldsSubqueryAndReason() {
        com.panfan.hivelineage.v2.syntax.SelectQuerySyntax query =
                (com.panfan.hivelineage.v2.syntax.SelectQuerySyntax) ((com.panfan.hivelineage.v2.syntax.QueryStatementSyntax)
                        parse("select a.id, (select max(b.id) from db.b b) from db.a a")).getQuery();
        AnalysisContract contract = contract(false, AnalysisCapability.COLUMN_LINEAGE);
        BindingWork work = new BindingWork(context(new TestMetadata(), contract,
                Collections.<DefinitionId, DiagnosticBag>emptyMap()),
                new BindingDemand(EnumSet.allOf(com.panfan.hivelineage.v2.ir.quality.BindingRequirement.class),
                        Collections.<com.panfan.hivelineage.v2.ir.identity.NodeId>emptySet()), new DefaultTypeAnalyzer());
        ReferenceScope scope = new ReferenceScope(work, null, new com.panfan.hivelineage.v2.ir.identity.ScopeId("test"),
                query.getQueryBlockId(), "", Collections.<com.panfan.hivelineage.v2.syntax.CteSyntax>emptyList());
        new QueryBinder(work).from(query.getBody().getFrom(), scope, true);
        List<com.panfan.hivelineage.v2.syntax.SyntaxExpression> children = new ArrayList<>();
        query.getBody().getSelectItems().forEach(item -> children.add(item.getExpression()));
        com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax unknown = new com.panfan.hivelineage.v2.syntax.UnknownExpressionSyntax(
                new com.panfan.hivelineage.v2.ir.identity.NodeId("unknown"), query.getSourceRef(), "FUTURE_OPERATOR", children);
        Expressions.UnknownExpression bound = (Expressions.UnknownExpression) new ExpressionBinder(work).bind(unknown, scope);
        assertEquals("FUTURE_OPERATOR", bound.getDiagnosticCode());
        assertEquals(2, bound.getKnownChildren().size());
        assertTrue(bound.getKnownChildren().get(1) instanceof Expressions.ScalarSubquery);
        assertEquals(2, work.objects.size());
        assertTrue(work.partial);
    }

    @Test
    public void negatedRangeAndMembershipHaveBooleanOutputs() {
        BindingResult result = bindDetailed("select 1 not between 2 and 3 as range_flag, 1 not in (2,3) as member_flag",
                new TestMetadata());
        List<com.panfan.hivelineage.v2.ir.relation.OutputSlot> slots =
                BindingWork.slots(result.getUnit().get().getQueryResults().get(0).getOutputSchema());
        assertEquals(2, slots.size());
        for (com.panfan.hivelineage.v2.ir.relation.OutputSlot slot : slots) {
            assertEquals(TypeRef.Kind.BOOLEAN, slot.getType().getKind());
        }
    }

    @Test
    public void temporarySourceSinkAndDdlUseTheSameIdentityAcrossDatabases() {
        TestMetadata metadata = new TestMetadata().table("other.t", "permanent_only");
        AnalysisContract contract = contract(false, AnalysisCapability.COLUMN_LINEAGE, AnalysisCapability.CATALOG_SIMULATION);
        BindingContext original = context(metadata, contract, Collections.<DefinitionId, DiagnosticBag>emptyMap());
        ObjectId tempId = new ObjectId("temporary-object");
        QualifiedName tempName = name("temporary_namespace", "t");
        RelationSchema tempSchema = new RelationSchema(SchemaState.KNOWN, Arrays.asList(new ColumnMetadata("id", 0,
                SemanticTypes.type(TypeRef.Kind.INTEGER), ColumnMetadata.Role.DATA, ColumnMetadata.Nullability.UNKNOWN)),
                provenance(), MetadataVersion.unknown());
        com.panfan.hivelineage.v2.session.CatalogEntry temp = new com.panfan.hivelineage.v2.session.CatalogEntry(
                new ObjectDescriptor(tempId, tempName, ObjectDescriptor.Kind.TEMPORARY_TABLE, Optional.<DefinitionRef>empty(),
                        provenance(), MetadataVersion.unknown()), Arrays.asList(tempName, name("t"), name("original", "t")),
                Optional.of(tempSchema), Optional.<com.panfan.hivelineage.v2.session.LogicalDefinition>empty());
        SessionSnapshot session = new SessionSnapshot(original.getSession().getInitialContext(), Optional.of(name("other")),
                original.getSession().getVariables(), Collections.singletonMap(tempName, temp),
                original.getSession().getPersistentOverlay(), original.getSession().getScriptDefinitions(),
                original.getSession().getDatasetVersions(), original.getSession().getUncertainty(), 1);
        BindingContext context = new BindingContext(session, metadata, readContext(), original.getDefinitions(),
                original.getFunctions(), original.getDialect(), contract, original.getFailedDefinitions());
        for (String target : Arrays.asList("t", "original.t")) {
            SyntaxStatement syntax = parse("insert into " + target + " select * from " + target);
            BindingResult result = new DefaultSemanticBinder().bind(syntax, context,
                    new DefaultAnalysisDemandPlanner().plan(syntax, contract));
            assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
            assertEquals(tempId, result.getUnit().get().getWrites().get(0).getTargetObject());
            Relations.Scan scan = (Relations.Scan) result.getUnit().get().getRelations().stream()
                    .filter(relation -> relation instanceof Relations.Scan).findFirst().get();
            assertEquals(tempId, scan.getObjectId());
            assertEquals(com.panfan.hivelineage.v2.ir.statement.BoundObjectReference.ObjectKind.TEMPORARY,
                    result.getUnit().get().getObjects().get(tempId).getKind());
        }
        SyntaxStatement alter = parse("alter table t add columns (added int)");
        BindingResult altered = new DefaultSemanticBinder().bind(alter, context,
                new DefaultAnalysisDemandPlanner().plan(alter, contract));
        Effects.AlterObject effect = (Effects.AlterObject) altered.getUnit().get().getEffects().get(0);
        assertEquals(tempId, effect.getReplacement().getObjectId());
        assertEquals(2, BindingWork.slots(effect.getReplacement().getSchema()).size());
        assertEquals(0, metadata.schemaReads);
    }

    @Test
    public void starJoinGroupingHavingWindowAndOrderingCompleteSourceSchemasBeforeAssembly() {
        BindingResult result = bindDetailed("select a.*, sum(b.score) as total, row_number() over(order by a.id) as rn "
                        + "from db.a a join db.b b on a.id=b.id group by a.id having total>0 order by total",
                new TestMetadata().table("db.a", "id").table("db.b", "id", "score"));
        assertNotEquals(BindingOutcome.FAILED, result.getOutcome());
        assertTrue(result.getUnit().get().getRelations().stream().anyMatch(relation -> relation instanceof Relations.Window));
        assertTrue(result.getUnit().get().getRelations().stream().anyMatch(relation -> relation instanceof Relations.Aggregate));
        assertTrue(result.getUnit().get().getRelations().stream().anyMatch(relation -> relation instanceof Relations.Sort));
        assertEquals(3, BindingWork.slots(result.getUnit().get().getQueryResults().get(0).getOutputSchema()).size());
    }

    private BindingResult bindDetailed(String sql, TestMetadata metadata) {
        SyntaxStatement syntax = parse(sql);
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(EnumSet.of(AnalysisCapability.COLUMN_LINEAGE)),
                VariablePolicy.CURRENT_API_FALLBACK, DefinitionPolicy.CURRENT_API_CTE_REUSE, StarMappingPolicy.POSITIONAL_EXACT,
                StatementPublicationPolicy.DETAILED, false, false, false, 5, false, "fallback");
        BindingDemand demand = new DefaultAnalysisDemandPlanner().plan(syntax, contract);
        BindingResult result = new DefaultSemanticBinder().bind(syntax,
                context(metadata, contract, Collections.<DefinitionId, DiagnosticBag>emptyMap()), demand);
        assertEquals(BindingOutcome.COMPLETE, result.getOutcome());
        VerificationResult verification = new DefaultIrVerifier().verify(result.getUnit().get(), demand);
        assertEquals(VerificationOutcome.ACCEPTED, verification.getOutcome());
        return result;
    }

    private BindingResult bind(String sql, TestMetadata metadata, AnalysisCapability... capabilities) {
        return bindInSession(sql, metadata, "session", capabilities);
    }

    private BindingResult bindInSession(String sql, TestMetadata metadata, String sessionId,
                                        AnalysisCapability... capabilities) {
        SyntaxStatement syntax = parse(sql);
        AnalysisContract contract = contract(false, capabilities);
        BindingResult result = new DefaultSemanticBinder().bind(syntax, context(metadata, contract,
                Collections.<DefinitionId, DiagnosticBag>emptyMap(), sessionId), new DefaultAnalysisDemandPlanner().plan(syntax, contract));
        assertTrue(result.getDiagnostics().getDiagnostics().toString(), result.getUnit().isPresent());
        if (result.getOutcome() == BindingOutcome.COMPLETE || result.getOutcome() == BindingOutcome.PARTIAL) {
            VerificationResult verified = new DefaultIrVerifier().verify(result.getUnit().get(),
                    new DefaultAnalysisDemandPlanner().plan(syntax, contract));
            List<String> messages = new ArrayList<>();
            for (com.panfan.hivelineage.v2.common.Diagnostic diagnostic : verified.getDiagnostics().getDiagnostics()) {
                messages.add(diagnostic.getCode() + ": " + diagnostic.getMessage());
            }
            assertEquals(sql + " " + messages, VerificationOutcome.ACCEPTED, verified.getOutcome());
        }
        return result;
    }

    private BindingDemand demand(SyntaxStatement syntax, AnalysisCapability... capabilities) {
        return new DefaultAnalysisDemandPlanner().plan(syntax, contract(false, capabilities));
    }

    private SyntaxStatement parse(String sql) {
        SourceRef source = new SourceRef("sql", new SourceSpan("sql", 0, sql.length()));
        ScriptUnit original = new ScriptUnit("sql", 0, sql, ScriptUnitKind.SQL, source);
        ExpandedUnit expanded = new ExpandedUnit(original, "sql-expanded", sql,
                new SourceMap("sql", "sql-expanded", Collections.<SourceMapEntry>emptyList()), Collections.<VariableEvidence>emptyList());
        HiveDialectFactory factory = new HiveDialectFactory();
        SyntaxResult syntax = new DialectSyntaxFrontend<HiveParseArtifact>(factory).parse(expanded, factory.getProfile());
        assertTrue(sql + " : " + syntax.getDiagnostics(), syntax.getStatement() != null);
        return syntax.getStatement();
    }

    private AnalysisContract contract(boolean expandView, AnalysisCapability... capabilities) {
        return new AnalysisContract(new AnalysisCapabilities(EnumSet.copyOf(Arrays.asList(capabilities))),
                VariablePolicy.CURRENT_API_FALLBACK, DefinitionPolicy.CURRENT_API_CTE_REUSE, StarMappingPolicy.POSITIONAL_EXACT,
                StatementPublicationPolicy.CURRENT_API, false, false, expandView, 5, false, "fallback");
    }

    private BindingContext context(TestMetadata metadata, AnalysisContract contract, Map<DefinitionId, DiagnosticBag> failures) {
        return context(metadata, contract, failures, "session");
    }

    private BindingContext context(TestMetadata metadata, AnalysisContract contract,
                                   Map<DefinitionId, DiagnosticBag> failures, String sessionId) {
        InitialSessionContext initial = new InitialSessionContext(sessionId, "wf", "task", "V1", "cluster",
                Optional.of(name("default")), Collections.<VariableKey, VariableState>emptyMap(), Collections.<String, String>emptyMap());
        return new BindingContext(SessionSnapshot.initial(initial), metadata, readContext(),
                Collections.<DefinitionId, PreparedDefinition>emptyMap(), new DefaultFunctionCatalog(),
                new HiveDialectFactory().getProfile(), contract, failures);
    }

    private static MetadataReadContext readContext() {
        return new MetadataReadContext("source", "cluster", Optional.<String>empty(), Collections.<String, String>emptyMap());
    }

    private static QualifiedName name(String... parts) {
        return new QualifiedName(Arrays.asList(parts));
    }

    private static MetadataProvenance provenance() {
        return new MetadataProvenance("source", MetadataProvenance.Origin.TEST_FIXTURE, Optional.<String>empty(), Optional.<Long>empty());
    }

    private static final class TestMetadata implements MetadataAccess {
        private final Map<String, RelationSchema> schemas = new LinkedHashMap<>();
        private final Map<String, ObjectDescriptor> views = new LinkedHashMap<>();
        private int schemaReads;
        private int objectReads;

        TestMetadata table(String name, String... columns) {
            List<ColumnMetadata> fields = new ArrayList<>();
            for (int index = 0; index < columns.length; index++) {
                fields.add(new ColumnMetadata(columns[index], index, TypeRef.unknown(), ColumnMetadata.Role.UNKNOWN, ColumnMetadata.Nullability.UNKNOWN));
            }
            schemas.put(name, new RelationSchema(SchemaState.KNOWN, fields, provenance(), MetadataVersion.unknown()));
            return this;
        }

        @Override
        public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context) {
            objectReads++;
            return views.containsKey(name.getDisplayName()) ? LookupResult.found(views.get(name.getDisplayName()))
                    : LookupResult.<ObjectDescriptor>unknown(DiagnosticBag.empty());
        }

        @Override
        public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context) {
            schemaReads++;
            return schemas.containsKey(name.getDisplayName()) ? LookupResult.found(schemas.get(name.getDisplayName()))
                    : LookupResult.<RelationSchema>unknown(DiagnosticBag.empty());
        }

        @Override
        public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
            throw new AssertionError("binder must select the requested facet");
        }
    }
}
