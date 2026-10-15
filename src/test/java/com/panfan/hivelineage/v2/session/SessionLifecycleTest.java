package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.BoundObjectReference;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;
import com.panfan.hivelineage.v2.ir.FieldIdentity;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.expression.QualifiedFieldHandle;
import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SessionLifecycleTest {
    @Test
    public void temporaryDropRevealsPermanentOverlayWithoutDeletingItAndKeepsOtherSessionsIsolated() {
        SessionSnapshot initial = initial("one");
        QualifiedName name = name("shared");
        SessionSnapshot created = commit(initial, Arrays.<BoundEffect>asList(create("permanent", name, Effects.ObjectKind.TABLE),
                create("temporary", name, Effects.ObjectKind.TEMPORARY_TABLE))).getSnapshot();
        assertTrue(initial.getTemporaryCatalog().isEmpty());
        assertTrue(initial.getPersistentOverlay().isEmpty());
        assertTrue(initial("two").getTemporaryCatalog().isEmpty());
        SessionSnapshot dropped = commit(created, Collections.<BoundEffect>singletonList(new Effects.DropObject(new NodeId("drop"), Effects.Certainty.CERTAIN,
                always(), new ObjectId("temporary"), Effects.ObjectKind.TEMPORARY_TABLE, name))).getSnapshot();
        assertFalse(dropped.getTemporaryCatalog().containsKey(name));
        assertEquals(OverlayEntry.State.UPSERTED, dropped.getPersistentOverlay().get(name).getState());
        assertEquals(new ObjectId("permanent"), dropped.getPersistentOverlay().get(name).getEntry().get().getObject().getObjectId());
        assertTrue(created.getTemporaryCatalog().containsKey(name));
    }

    @Test
    public void externalPermanentDropCreatesTombstoneWithoutParsingOpaqueObjectId() {
        QualifiedName actualName = name("external");
        Effects.DropObject effect = new Effects.DropObject(new NodeId("drop"), Effects.Certainty.CERTAIN,
                new Effects.Precondition(Effects.Precondition.Kind.OBJECT_PRESENT, new ObjectId("opaque-7")),
                new ObjectId("opaque-7"), Effects.ObjectKind.TABLE, actualName);
        SessionCommitResult result = commit(initial("one"), Collections.<BoundEffect>singletonList(effect));
        assertEquals(CommitOutcome.COMMITTED, result.getOutcome());
        assertEquals(OverlayEntry.State.DELETED, result.getSnapshot().getPersistentOverlay().get(actualName).getState());
    }

    @Test
    public void renamePreservesObjectIdentityAndDataVersionAndLeavesOldPermanentNameDeleted() {
        SessionSnapshot created = commit(initial("one"), Collections.<BoundEffect>singletonList(create("object", name("old"), Effects.ObjectKind.TABLE))).getSnapshot();
        DataVersionState version = new DataVersionState(VersionRef.known(new DatasetVersionId("version-2")), Collections.emptyList(), DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS);
        SessionSnapshot before = withVersion(created, new ObjectId("object"), version);
        SessionSnapshot after = commit(before, Collections.<BoundEffect>singletonList(new Effects.RenameObject(new NodeId("rename"), Effects.Certainty.CERTAIN,
                always(), new ObjectId("object"), name("new"), name("old")))).getSnapshot();
        assertEquals(OverlayEntry.State.DELETED, after.getPersistentOverlay().get(name("old")).getState());
        assertEquals(new ObjectId("object"), after.getPersistentOverlay().get(name("new")).getEntry().get().getObject().getObjectId());
        assertSame(version, after.getDatasetVersions().get(new ObjectId("object")));
        assertTrue(before.getPersistentOverlay().get(name("old")).getEntry().isPresent());
    }

    @Test
    public void renameCannotClearAnObjectsEarlierSchemaOrDataVersionUncertainty() {
        for (Effects.ObjectKind kind : Arrays.asList(Effects.ObjectKind.TABLE, Effects.ObjectKind.TEMPORARY_TABLE)) {
            ObjectId object = new ObjectId("uncertain-object");
            SessionSnapshot created = commit(initial("one"), Collections.<BoundEffect>singletonList(
                create(object.getValue(), name("old"), kind))).getSnapshot();
            DataVersionState version = new DataVersionState(VersionRef.known(new DatasetVersionId("before.unknown.effect")),
                Collections.singletonList(new DatasetVersionId("predecessor")), DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS);
            SessionSnapshot uncertain = commit(withVersion(created, object, version), Collections.<BoundEffect>singletonList(
                new Effects.UnknownEffect(new NodeId("unknown"), Effects.Certainty.UNKNOWN, always(),
                    Collections.singletonList(object), Collections.emptyList(), false, "CATALOG_ACTION_NOT_BOUND"))).getSnapshot();
            SessionCommitResult renamed = commit(uncertain, Collections.<BoundEffect>singletonList(
                new Effects.RenameObject(new NodeId("rename"), Effects.Certainty.CERTAIN, always(), object, name("new"), name("old"))));
            assertEquals(CommitOutcome.UNCERTAIN, renamed.getOutcome());
            assertTrue(renamed.getSnapshot().getUncertainty().getObjects().contains(object));
            assertFalse(renamed.getSnapshot().getUncertainty().isCatalogUncertain());
            DataVersionState after = renamed.getSnapshot().getDatasetVersions().get(object);
            assertEquals(VersionRef.State.UNKNOWN, after.getVersion().getState());
            assertEquals(DataVersionState.Basis.UNKNOWN, after.getBasis());
            assertEquals(version.getPredecessors(), after.getPredecessors());
        }
    }

    @Test
    public void staleRevisionCannotPartiallyApplyPlan() {
        SessionSnapshot before = initial("one");
        SessionCommitResult result = new DefaultSessionEffectCommitter().commit(before, new SessionEffectPlan(2,
                Collections.<BoundEffect>singletonList(create("table", name("table"), Effects.ObjectKind.TABLE)), DiagnosticBag.empty()));
        assertEquals(CommitOutcome.STALE_REVISION, result.getOutcome());
        assertSame(before, result.getSnapshot());
        assertTrue(before.getPersistentOverlay().isEmpty());
    }

    @Test
    public void unknownVariableAndDatabaseEffectsInvalidateCertaintyAndKnownAssignmentsRestoreIt() {
        Effects.SessionKey key = new Effects.SessionKey(Effects.Namespace.HIVEVAR, "day");
        SessionSnapshot known = commit(initial("one"), Collections.<BoundEffect>singletonList(new Effects.SetVariable(new NodeId("set"), Effects.Certainty.CERTAIN, always(), key, "known"))).getSnapshot();
        SessionCommitResult unknown = commit(known, Arrays.<BoundEffect>asList(
                new Effects.SetVariable(new NodeId("unknown.var"), Effects.Certainty.UNKNOWN, always(), key, "possibly-new"),
                new Effects.SetDatabase(new NodeId("unknown.db"), Effects.Certainty.UNKNOWN, always(), name("maybe"))));
        VariableKey variable = new VariableKey(VariableKey.Namespace.HIVEVAR, "day");
        assertEquals(CommitOutcome.UNCERTAIN, unknown.getOutcome());
        assertEquals(VariableState.Resolution.UNRESOLVED, unknown.getSnapshot().getVariables().get(variable).getResolution());
        assertTrue(unknown.getSnapshot().getUncertainty().isDatabaseUncertain());
        SessionSnapshot restored = commit(unknown.getSnapshot(), Arrays.<BoundEffect>asList(
                new Effects.SetVariable(new NodeId("known.var"), Effects.Certainty.CERTAIN, always(), key, "restored"),
                new Effects.SetDatabase(new NodeId("known.db"), Effects.Certainty.CERTAIN, always(), name("definite")))).getSnapshot();
        assertEquals("restored", restored.getVariables().get(variable).getValue().get());
        assertFalse(restored.getUncertainty().getVariables().contains(variable));
        assertFalse(restored.getUncertainty().isDatabaseUncertain());
    }

    @Test
    public void conditionalCreateDoesNotInventSchemaWhenExternalExistenceIsUnknown() {
        Effects.CreateObject create = new Effects.CreateObject(new NodeId("create"), Effects.Certainty.CONDITIONAL,
                new Effects.Precondition(Effects.Precondition.Kind.OBJECT_ABSENT, new ObjectId("external")),
                new Effects.ObjectShape(new ObjectId("external"), name("external"), Effects.ObjectKind.TABLE, OutputSchema.known(Collections.emptyList()), VersionRef.notRequested()));
        SessionCommitResult result = commit(initial("one"), Collections.<BoundEffect>singletonList(create));
        assertEquals(CommitOutcome.UNCERTAIN, result.getOutcome());
        assertEquals(OverlayEntry.State.UNKNOWN, result.getSnapshot().getPersistentOverlay().get(name("external")).getState());
        assertFalse(result.getSnapshot().getPersistentOverlay().get(name("external")).getEntry().isPresent());
    }

    @Test
    public void partitionOverwriteRetainsPriorDatasetVersionWhileWholeOverwriteReplacesIt() {
        ObjectId target = new ObjectId("target");
        BoundWrite partitioned = versionedWrite(PartitionScope.Kind.STATIC);
        SessionEffectPlan partitionPlan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(),
                Optional.of(AnalysisFixtures.unit(Collections.<BoundRelation>emptyList(), Collections.singletonList(partitioned))), Collections.emptyMap());
        SessionSnapshot partitionAfter = new DefaultSessionEffectCommitter().commit(initial("one"), partitionPlan).getSnapshot();
        assertEquals(Collections.singletonList(new DatasetVersionId("before")), partitionAfter.getDatasetVersions().get(target).getPredecessors());
        BoundWrite whole = versionedWrite(PartitionScope.Kind.UNPARTITIONED);
        SessionEffectPlan wholePlan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(),
                Optional.of(AnalysisFixtures.unit(Collections.<BoundRelation>emptyList(), Collections.singletonList(whole))), Collections.emptyMap());
        SessionSnapshot wholeAfter = new DefaultSessionEffectCommitter().commit(initial("one"), wholePlan).getSnapshot();
        assertTrue(wholeAfter.getDatasetVersions().get(target).getPredecessors().isEmpty());
        assertEquals(DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS, wholeAfter.getDatasetVersions().get(target).getBasis());
    }

    @Test
    public void temporaryDefinitionCreatedWithinSamePlanKeepsTemporaryKind() {
        QualifiedName name = name("tmp");
        Effects.RegisterDefinition register = new Effects.RegisterDefinition(new NodeId("register"), Effects.Certainty.CERTAIN, always(),
                new DefinitionId("definition"), name, OutputSchema.notRequested(), new NodeId("query"), new ObjectId("tmp"));
        SessionSnapshot after = commit(initial("one"), Arrays.<BoundEffect>asList(create("tmp", name, Effects.ObjectKind.TEMPORARY_TABLE), register)).getSnapshot();
        assertEquals(LogicalDefinition.Kind.TEMPORARY_RELATION, after.getScriptDefinitions().get(name).getKind());
        assertEquals(LogicalDefinition.Kind.TEMPORARY_RELATION, after.getTemporaryCatalog().get(name).getDefinition().get().getKind());
    }

    @Test
    public void conditionalCtasWithUnknownExistenceCannotPublishKnownAfterVersion() {
        BoundWrite write = versionedWrite(PartitionScope.Kind.UNPARTITIONED);
        Effects.CreateObject conditional = new Effects.CreateObject(new NodeId("conditional"), Effects.Certainty.CONDITIONAL,
                new Effects.Precondition(Effects.Precondition.Kind.OBJECT_ABSENT, new ObjectId("target")),
                new Effects.ObjectShape(new ObjectId("target"), name("target"), Effects.ObjectKind.TABLE, OutputSchema.known(Collections.emptyList()), write.getAfterVersion()));
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.<BoundEffect>singletonList(conditional), DiagnosticBag.empty(),
                Optional.of(AnalysisFixtures.unit(Collections.<BoundRelation>emptyList(), Collections.singletonList(write))), Collections.emptyMap());
        SessionCommitResult result = new DefaultSessionEffectCommitter().commit(initial("one"), plan);
        assertEquals(CommitOutcome.UNCERTAIN, result.getOutcome());
        assertEquals(VersionRef.State.UNKNOWN, result.getSnapshot().getDatasetVersions().get(new ObjectId("target")).getVersion().getState());
        assertEquals(DataVersionState.Basis.UNKNOWN, result.getSnapshot().getDatasetVersions().get(new ObjectId("target")).getBasis());
        assertEquals(Collections.singletonList(new DatasetVersionId("before")), result.getSnapshot().getDatasetVersions().get(new ObjectId("target")).getPredecessors());
    }

    @Test
    public void skippedCreateDoesNotOverwriteExistingVersionOrInstallNewDefinition() {
        SessionSnapshot created = commit(initial("one"), Collections.<BoundEffect>singletonList(create("target", name("target"), Effects.ObjectKind.TABLE))).getSnapshot();
        DataVersionState original = new DataVersionState(VersionRef.known(new DatasetVersionId("existing")), Collections.emptyList(), DataVersionState.Basis.SCRIPT_ENTRY);
        SessionSnapshot before = withVersion(created, new ObjectId("target"), original);
        Effects.CreateObject skipped = new Effects.CreateObject(new NodeId("skipped"), Effects.Certainty.CONDITIONAL,
                new Effects.Precondition(Effects.Precondition.Kind.OBJECT_ABSENT, new ObjectId("target")),
                new Effects.ObjectShape(new ObjectId("target"), name("target"), Effects.ObjectKind.TABLE, OutputSchema.notRequested(), VersionRef.known(new DatasetVersionId("after"))));
        Effects.RegisterDefinition registration = new Effects.RegisterDefinition(new NodeId("register"), Effects.Certainty.CERTAIN, always(),
                new DefinitionId("new.definition"), name("target"), OutputSchema.notRequested(), new NodeId("query"), new ObjectId("target"));
        SessionEffectPlan plan = new SessionEffectPlan(before.getRevision(), Arrays.<BoundEffect>asList(skipped, registration), DiagnosticBag.empty(),
                Optional.of(AnalysisFixtures.unit(Collections.<BoundRelation>emptyList(), Collections.singletonList(versionedWrite(PartitionScope.Kind.UNPARTITIONED)))), Collections.emptyMap());
        SessionSnapshot after = new DefaultSessionEffectCommitter().commit(before, plan).getSnapshot();
        assertSame(original, after.getDatasetVersions().get(new ObjectId("target")));
        assertFalse(after.getScriptDefinitions().containsKey(name("target")));
    }

    @Test
    public void dropTombstoneSuppressesAnyStaleWriteVersionInSamePlan() {
        Effects.DropObject drop = new Effects.DropObject(new NodeId("drop"), Effects.Certainty.CERTAIN,
                new Effects.Precondition(Effects.Precondition.Kind.OBJECT_PRESENT, new ObjectId("target")),
                new ObjectId("target"), Effects.ObjectKind.TABLE, name("target"));
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.<BoundEffect>singletonList(drop), DiagnosticBag.empty(),
                Optional.of(AnalysisFixtures.unit(Collections.<BoundRelation>emptyList(), Collections.singletonList(versionedWrite(PartitionScope.Kind.UNPARTITIONED)))), Collections.emptyMap());
        SessionSnapshot after = new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot();
        assertEquals(OverlayEntry.State.DELETED, after.getPersistentOverlay().get(name("target")).getState());
        assertFalse(after.getDatasetVersions().containsKey(new ObjectId("target")));
    }

    @Test
    public void sourceVersionsFollowEachWriteRootAndSelfReadSurvivesWholeOverwrite() {
        Relations.Scan leftBase = AnalysisFixtures.scan("left", "left.object", "main");
        Relations.Scan rightBase = AnalysisFixtures.scan("right", "right.object", "main");
        Relations.Scan left = new Relations.Scan(leftBase.getId(), leftBase.getOutputSchema(), leftBase.getQueryBlockId(), 0, null,
                leftBase.getObjectId(), VersionRef.known(new DatasetVersionId("left.input")), leftBase.getInstanceId());
        Relations.Scan right = new Relations.Scan(rightBase.getId(), rightBase.getOutputSchema(), rightBase.getQueryBlockId(), 0, null,
                rightBase.getObjectId(), VersionRef.known(new DatasetVersionId("right.input")), rightBase.getInstanceId());
        BoundWrite first = new BoundWrite(new NodeId("first.write"), left.getObjectId(), left.getVersion(), VersionRef.known(new DatasetVersionId("left.after")),
                BoundWrite.WriteMode.OVERWRITE, Collections.emptyList(), new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()),
                null, MappingCoverage.COMPLETE, Collections.emptyList(), left.getId());
        BoundWrite second = new BoundWrite(new NodeId("second.write"), new ObjectId("sink"), VersionRef.known(new DatasetVersionId("sink.before")),
                VersionRef.known(new DatasetVersionId("sink.after")), BoundWrite.WriteMode.CREATE_AS, Collections.emptyList(),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE, Collections.emptyList(), right.getId());
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(), Optional.of(
                AnalysisFixtures.unit(Arrays.<BoundRelation>asList(left, right), Arrays.asList(first, second))), Collections.emptyMap());
        SessionSnapshot after = new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot();
        assertEquals(Collections.singletonList(new DatasetVersionId("left.input")), after.getDatasetVersions().get(left.getObjectId()).getPredecessors());
        assertEquals(Collections.singletonList(new DatasetVersionId("right.input")), after.getDatasetVersions().get(new ObjectId("sink")).getPredecessors());
    }

    @Test
    public void uncertainReadVersionDoesNotBecomeKnownThroughWholeOverwrite() {
        Relations.Scan base = AnalysisFixtures.scan("source", "source.object", "main");
        Relations.Scan source = new Relations.Scan(base.getId(), base.getOutputSchema(), base.getQueryBlockId(), 0, null,
                base.getObjectId(), VersionRef.unknown("READ_VERSION_UNCERTAIN"), base.getInstanceId());
        BoundWrite write = new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.known(new DatasetVersionId("before")),
                VersionRef.known(new DatasetVersionId("after")), BoundWrite.WriteMode.OVERWRITE, Collections.emptyList(),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE, Collections.emptyList(), source.getId());
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(), Optional.of(
                AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(source), Collections.singletonList(write))), Collections.emptyMap());
        DataVersionState result = new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot().getDatasetVersions().get(new ObjectId("target"));
        assertEquals(VersionRef.State.UNKNOWN, result.getVersion().getState());
        assertEquals(DataVersionState.Basis.UNKNOWN, result.getBasis());
        assertEquals(Collections.singletonList(new DatasetVersionId("before")), result.getPredecessors());
    }

    @Test
    public void writeFieldReferencesRetainSourceVersionsWithoutOptionalQueryRoot() {
        Relations.Scan base = AnalysisFixtures.scan("source", "source.object", "main", "id");
        Relations.Scan source = new Relations.Scan(base.getId(), base.getOutputSchema(), base.getQueryBlockId(), 0, null,
                base.getObjectId(), VersionRef.known(new DatasetVersionId("source.version")), base.getInstanceId());
        Relations.Scan otherBase = AnalysisFixtures.scan("unrelated", "other.object", "main", "id");
        Relations.Scan unrelated = new Relations.Scan(otherBase.getId(), otherBase.getOutputSchema(), otherBase.getQueryBlockId(), 0, null,
                otherBase.getObjectId(), VersionRef.known(new DatasetVersionId("unrelated.version")), otherBase.getInstanceId());
        BoundWrite write = new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.known(new DatasetVersionId("before")),
                VersionRef.known(new DatasetVersionId("after")), BoundWrite.WriteMode.OVERWRITE,
                Collections.singletonList(new WriteAssignment(new FieldIdentity("target.id", "id"),
                        AnalysisFixtures.ref("source.reference", "source.id"))),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE,
                Collections.emptyList());
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(source, unrelated), Collections.singletonList(write));
        AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.DATASET_VERSIONS);
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(), Optional.of(unit), Collections.emptyMap());
        DataVersionState result = new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot().getDatasetVersions().get(write.getTargetObject());
        assertEquals(Collections.singletonList(new DatasetVersionId("source.version")), result.getPredecessors());
    }

    @Test
    public void lightweightFieldReferenceCannotLoseItsUnknownSourceVersion() {
        Relations.Scan source = new Relations.Scan(new NodeId("source"), OutputSchema.notRequested(), new QueryBlockId("main"), 0, null,
                new ObjectId("source.object"), VersionRef.unknown("READ_VERSION_UNCERTAIN"), new RelationInstanceId("source.instance"));
        QualifiedFieldHandle expression = new QualifiedFieldHandle(new NodeId("field"), TypeRef.unknown(), null, source.getInstanceId(),
                new FieldIdentity("source.id", "id"), Collections.emptyList(), QualifiedFieldHandle.ExistenceState.UNVERIFIED);
        BoundWrite write = new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.known(new DatasetVersionId("before")),
                VersionRef.known(new DatasetVersionId("after")), BoundWrite.WriteMode.OVERWRITE,
                Collections.singletonList(new WriteAssignment(new FieldIdentity("target.id", "id"), expression)),
                new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null, MappingCoverage.COMPLETE, Collections.emptyList());
        BoundAnalysisUnit unit = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(source), Collections.singletonList(write));
        AnalysisFixtures.verify(unit, BindingRequirement.OBJECTS, BindingRequirement.DATASET_VERSIONS);
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(), Optional.of(unit), Collections.emptyMap());
        DataVersionState result = new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot().getDatasetVersions().get(write.getTargetObject());
        assertEquals(VersionRef.State.UNKNOWN, result.getVersion().getState());
        assertEquals(DataVersionState.Basis.UNKNOWN, result.getBasis());
        assertEquals(Collections.singletonList(new DatasetVersionId("before")), result.getPredecessors());
    }

    @Test
    public void temporaryMaterializationStopsAtItsVersionWhileViewUsesCurrentUnderlyingVersions() {
        Relations.Scan base = AnalysisFixtures.scan("base", "base.object", "definition");
        Relations.Scan updatedBase = new Relations.Scan(base.getId(), base.getOutputSchema(), base.getQueryBlockId(), 0, null,
                base.getObjectId(), VersionRef.known(new DatasetVersionId("base.updated")), base.getInstanceId());
        Relations.DefinitionInstance temporary = definition("temporary", updatedBase, "temp.snapshot");
        Relations.DefinitionInstance view = definition("view", updatedBase, "view.logical");
        BoundWrite tempWrite = sourcedWrite("from.temp", temporary.getId());
        BoundWrite viewWrite = sourcedWrite("from.view", view.getId());
        BoundAnalysisUnit original = AnalysisFixtures.unit(Arrays.<BoundRelation>asList(updatedBase, temporary, view), Arrays.asList(tempWrite, viewWrite));
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<>(original.getObjects());
        objects.put(temporary.getPhysicalObject().get(), new BoundObjectReference(temporary.getPhysicalObject().get(), name("tmp"), BoundObjectReference.ObjectKind.TEMPORARY));
        objects.put(view.getPhysicalObject().get(), new BoundObjectReference(view.getPhysicalObject().get(), name("view"), BoundObjectReference.ObjectKind.VIEW));
        BoundAnalysisUnit unit = new BoundAnalysisUnit(original.getStatementId(), original.getRelations(), original.getQueryResults(), original.getWrites(), original.getEffects(),
                original.getBindingCoverage(), original.getDiagnostics(), original.getNotRequestedRegions(), original.getExecutionAssumption(), objects);
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(), Optional.of(unit), Collections.emptyMap());
        SessionSnapshot after = new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot();
        assertEquals(Collections.singletonList(new DatasetVersionId("temp.snapshot")), after.getDatasetVersions().get(tempWrite.getTargetObject()).getPredecessors());
        assertEquals(Collections.singletonList(new DatasetVersionId("base.updated")), after.getDatasetVersions().get(viewWrite.getTargetObject()).getPredecessors());
    }

    @Test
    public void declaredTemporaryLookupNamesSurviveCommitAndDefinitionRegistration() {
        QualifiedName canonical = name("canonical");
        List<QualifiedName> aliases = new ArrayList<>(Arrays.asList(canonical, new QualifiedName(Arrays.asList("source_db", "tmp")),
                new QualifiedName(Collections.singletonList("tmp"))));
        Effects.ObjectShape shape = new Effects.ObjectShape(new ObjectId("temp"), canonical, Effects.ObjectKind.TEMPORARY_TABLE,
                OutputSchema.notRequested(), VersionRef.notRequested(), aliases);
        aliases.clear();
        Effects.RegisterDefinition register = new Effects.RegisterDefinition(new NodeId("register"), Effects.Certainty.CERTAIN, always(),
                new DefinitionId("definition"), canonical, OutputSchema.notRequested(), new NodeId("query"), shape.getObjectId());
        SessionSnapshot after = commit(initial("one"), Arrays.<BoundEffect>asList(new Effects.CreateObject(new NodeId("create"),
                Effects.Certainty.CERTAIN, always(), shape), register)).getSnapshot();
        assertEquals(3, after.getTemporaryCatalog().get(canonical).getLookupNames().size());
        assertTrue(after.getTemporaryCatalog().get(canonical).getLookupNames().contains(new QualifiedName(Arrays.asList("source_db", "tmp"))));
    }

    @Test
    public void definingViewDoesNotCommitAMaterializedDatasetVersion() {
        BoundWrite write = sourcedWrite("define.view", new NodeId("source"));
        BoundAnalysisUnit original = AnalysisFixtures.unit(Collections.<BoundRelation>singletonList(AnalysisFixtures.scan("source", "base", "main")),
                Collections.singletonList(write));
        Map<ObjectId, BoundObjectReference> objects = new LinkedHashMap<>(original.getObjects());
        objects.put(write.getTargetObject(), new BoundObjectReference(write.getTargetObject(), name("view"), BoundObjectReference.ObjectKind.VIEW));
        BoundAnalysisUnit unit = new BoundAnalysisUnit(original.getStatementId(), original.getRelations(), original.getQueryResults(), original.getWrites(), original.getEffects(),
                original.getBindingCoverage(), original.getDiagnostics(), original.getNotRequestedRegions(), original.getExecutionAssumption(), objects);
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.emptyList(), DiagnosticBag.empty(), Optional.of(unit), Collections.emptyMap());
        assertTrue(new DefaultSessionEffectCommitter().commit(initial("one"), plan).getSnapshot().getDatasetVersions().isEmpty());
    }

    private Relations.DefinitionInstance definition(String id, Relations.Scan input, String version) {
        return new Relations.DefinitionInstance(new NodeId(id), OutputSchema.known(Collections.emptyList()), new QueryBlockId("main"), 1, null,
                input.getId(), new DefinitionId(id + ".definition"), new RelationInstanceId(id + ".instance"), VersionRef.known(new DatasetVersionId(version)),
                Collections.emptyList(), new ObjectId(id + ".object"));
    }

    private BoundWrite sourcedWrite(String id, NodeId root) {
        return new BoundWrite(new NodeId(id), new ObjectId(id + ".target"), VersionRef.notRequested(), VersionRef.known(new DatasetVersionId(id + ".after")),
                BoundWrite.WriteMode.CREATE_AS, Collections.emptyList(), new PartitionScope(PartitionScope.Kind.UNPARTITIONED, Collections.emptyList()), null,
                MappingCoverage.COMPLETE, Collections.emptyList(), root);
    }

    private BoundWrite versionedWrite(PartitionScope.Kind kind) {
        return new BoundWrite(new NodeId("write"), new ObjectId("target"), VersionRef.known(new DatasetVersionId("before")),
                VersionRef.known(new DatasetVersionId("after")), BoundWrite.WriteMode.OVERWRITE, Collections.emptyList(),
                new PartitionScope(kind, Collections.emptyList()), null, MappingCoverage.COMPLETE, Collections.emptyList());
    }

    private SessionSnapshot withVersion(SessionSnapshot before, ObjectId object, DataVersionState version) {
        return new SessionSnapshot(before.getInitialContext(), before.getCurrentDatabase(), before.getVariables(), before.getTemporaryCatalog(), before.getPersistentOverlay(),
                before.getScriptDefinitions(), Collections.singletonMap(object, version), before.getUncertainty(), before.getRevision());
    }

    private SessionCommitResult commit(SessionSnapshot before, List<BoundEffect> effects) {
        return new DefaultSessionEffectCommitter().commit(before, new SessionEffectPlan(before.getRevision(), effects, DiagnosticBag.empty()));
    }

    private Effects.CreateObject create(String object, QualifiedName name, Effects.ObjectKind kind) {
        return new Effects.CreateObject(new NodeId("create." + object), Effects.Certainty.CERTAIN, always(),
                new Effects.ObjectShape(new ObjectId(object), name, kind, OutputSchema.known(Collections.emptyList()), VersionRef.notRequested()));
    }

    private Effects.Precondition always() {
        return new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
    }

    private QualifiedName name(String table) {
        return new QualifiedName(Arrays.asList("db", table));
    }

    private SessionSnapshot initial(String session) {
        return SessionSnapshot.initial(new InitialSessionContext(session, "workflow", "task", "version", "cluster", Optional.of(name("default")),
                Collections.emptyMap(), Collections.emptyMap()));
    }
}
