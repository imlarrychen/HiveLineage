package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.analysis.AnalysisFixtures;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.identity.QueryBlockId;
import com.panfan.hivelineage.v2.ir.identity.RelationInstanceId;
import com.panfan.hivelineage.v2.ir.quality.MappingCoverage;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.ir.relation.OutputSchema;
import com.panfan.hivelineage.v2.ir.relation.Relations;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.BoundWrite;
import com.panfan.hivelineage.v2.ir.statement.Effects;
import com.panfan.hivelineage.v2.ir.statement.PartitionScope;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.ir.statement.WriteAssignment;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Participants enforce their contracts without running the complete analysis pipeline. */
public class SessionParticipantsTest {
    @Test
    public void catalogRenameMovesDefinitionAndAllTemporaryAliasesTogether() {
        SessionSnapshot before = SessionTransactionContractTest.initial();
        ObjectId object = new ObjectId("temporary");
        QualifiedName oldName = name("db", "old");
        QualifiedName newName = name("db", "new");
        Effects.CreateObject create = new Effects.CreateObject(new NodeId("create"), Effects.Certainty.CERTAIN, always(),
                new Effects.ObjectShape(object, oldName, Effects.ObjectKind.TEMPORARY_TABLE, OutputSchema.notRequested(),
                        VersionRef.notRequested(), Arrays.asList(oldName, name("alias", "old"), name("old"))));
        Effects.RegisterDefinition register = new Effects.RegisterDefinition(new NodeId("register"), Effects.Certainty.CERTAIN,
                always(), new DefinitionId("definition"), oldName, OutputSchema.notRequested(), new NodeId("root"), object);
        SessionEffectPlan plan = new SessionEffectPlan(0, Arrays.<BoundEffect>asList(create, register), DiagnosticBag.empty());
        CatalogTransaction catalog = new CatalogTransaction(before, plan);
        catalog.create(SessionCatalogConversions.entry(create.getShape(), before));
        LogicalDefinition definition = SessionCatalogConversions.definition(register, plan, before);
        catalog.register(definition, register);
        assertTrue(catalog.rename(new Effects.RenameObject(new NodeId("rename"), Effects.Certainty.CERTAIN,
                always(), object, newName, oldName)));
        assertFalse(catalog.temporary().containsKey(oldName));
        assertEquals(Arrays.asList(newName, name("alias", "new"), name("new")), catalog.temporary().get(newName).getLookupNames());
        assertSame(definition, catalog.temporary().get(newName).getDefinition().get());
        assertSame(definition, catalog.definitions().get(newName));
        assertFalse(catalog.definitions().containsKey(oldName));
        assertTrue(before.getTemporaryCatalog().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> catalog.temporary().clear());
    }

    @Test
    public void variableTaintAndResolutionHaveOneOwnedValueAndOneUncertaintyRecord() {
        SessionSnapshot before = SessionTransactionContractTest.initial();
        SessionEffectPlan plan = new SessionEffectPlan(0, Collections.<BoundEffect>emptyList(), DiagnosticBag.empty());
        SessionBindings bindings = new SessionBindings(before);
        CatalogTransaction catalog = new CatalogTransaction(before, plan);
        UncertaintyTracker uncertainty = new UncertaintyTracker(before.getUncertainty(), DiagnosticBag.empty());
        Effects.SessionKey key = new Effects.SessionKey(Effects.Namespace.HIVECONF, "value");
        uncertainty.mark(new Effects.SetVariable(new NodeId("set"), Effects.Certainty.UNKNOWN, always(), key, "possible"), catalog, bindings);
        VariableKey variable = new VariableKey(VariableKey.Namespace.HIVECONF, "value");
        assertEquals(VariableState.Resolution.UNRESOLVED, bindings.variables().get(variable).getResolution());
        assertFalse(bindings.variables().get(variable).getValue().isPresent());
        assertTrue(uncertainty.snapshot().getVariables().contains(variable));
        uncertainty.resolve(bindings.setVariable(key, "certain"));
        assertEquals("certain", bindings.variables().get(variable).getValue().get());
        assertFalse(uncertainty.snapshot().getVariables().contains(variable));
        assertEquals(CommitOutcome.UNCERTAIN, uncertainty.outcome());
        assertTrue(before.getVariables().isEmpty());
    }

    @Test
    public void sourceCollectorReusesThePlanIndexWithoutLeakingVisitedNodesBetweenWrites() {
        Relations.Scan first = scan("first", "version.first");
        Relations.Scan second = scan("second", "version.second");
        BoundWrite firstWrite = write("first.write", first.getId(), BoundWrite.WriteMode.CREATE_AS, PartitionScope.Kind.UNPARTITIONED);
        BoundWrite secondWrite = write("second.write", second.getId(), BoundWrite.WriteMode.CREATE_AS, PartitionScope.Kind.UNPARTITIONED);
        SourceVersionCollector collector = new SourceVersionCollector(AnalysisFixtures.unit(
                Arrays.<BoundRelation>asList(first, second), Arrays.asList(firstWrite, secondWrite)));
        SourceVersionCollector.SourceVersions firstResult = collector.collect(firstWrite);
        assertEquals(Collections.singleton(new DatasetVersionId("version.first")), firstResult.ids());
        assertEquals(Collections.singleton(new DatasetVersionId("version.second")), collector.collect(secondWrite).ids());
        assertEquals(firstResult.ids(), collector.collect(firstWrite).ids());
        assertFalse(firstResult.isUnknown());
        assertThrows(UnsupportedOperationException.class, () -> firstResult.ids().clear());
    }

    @Test
    public void transitionPolicyKeepsPredecessorsForPartialWritesAndPreservesKnownRenameIdentity() {
        SourceVersionCollector.SourceVersions source = new SourceVersionCollector.SourceVersions(
                new LinkedHashSet<>(Collections.singletonList(new DatasetVersionId("source"))), false);
        VersionTransitionPolicy versions = new VersionTransitionPolicy(Collections.<ObjectId, DataVersionState>emptyMap());
        BoundWrite partitioned = write("target", new NodeId("root"), BoundWrite.WriteMode.OVERWRITE, PartitionScope.Kind.STATIC);
        DataVersionState partitionVersion = versions.transition(partitioned, source, false);
        assertEquals(Arrays.asList(new DatasetVersionId("source"), new DatasetVersionId("target.before")), partitionVersion.getPredecessors());
        BoundWrite whole = write("target", new NodeId("root"), BoundWrite.WriteMode.OVERWRITE, PartitionScope.Kind.UNPARTITIONED);
        assertEquals(Collections.singletonList(new DatasetVersionId("source")), versions.transition(whole, source, false).getPredecessors());
        ObjectId object = new ObjectId("target");
        VersionTransitionPolicy renamed = new VersionTransitionPolicy(Collections.singletonMap(object, partitionVersion));
        renamed.rename(object, false);
        assertSame(partitionVersion, renamed.snapshot().get(object));
        Map<ObjectId, DataVersionState> knownSnapshot = renamed.snapshot();
        renamed.rename(object, true);
        assertSame(partitionVersion, knownSnapshot.get(object));
        assertEquals(VersionRef.State.UNKNOWN, renamed.snapshot().get(object).getVersion().getState());
        assertEquals(partitionVersion.getPredecessors(), renamed.snapshot().get(object).getPredecessors());
        renamed.drop(object);
        assertTrue(renamed.isSuppressed(object));
        assertTrue(renamed.snapshot().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> renamed.snapshot().clear());
    }

    private static Relations.Scan scan(String id, String version) {
        return new Relations.Scan(new NodeId(id), OutputSchema.notRequested(), new QueryBlockId("main"), 0, null,
                new ObjectId(id + ".object"), VersionRef.known(new DatasetVersionId(version)), new RelationInstanceId(id + ".instance"));
    }

    private static BoundWrite write(String id, NodeId source, BoundWrite.WriteMode mode, PartitionScope.Kind partitions) {
        return new BoundWrite(new NodeId(id), new ObjectId(id + ".target"), VersionRef.known(new DatasetVersionId(id + ".before")),
                VersionRef.known(new DatasetVersionId(id + ".after")), mode, Collections.<WriteAssignment>emptyList(),
                new PartitionScope(partitions, Collections.<PartitionScope.Assignment>emptyList()), null, MappingCoverage.COMPLETE,
                Collections.emptyList(), source);
    }

    private static Effects.Precondition always() {
        return new Effects.Precondition(Effects.Precondition.Kind.ALWAYS, null);
    }

    private static QualifiedName name(String... parts) {
        return new QualifiedName(Arrays.asList(parts));
    }
}
