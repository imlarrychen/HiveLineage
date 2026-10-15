package com.panfan.hivelineage.v2.session;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.identity.DatasetVersionId;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.BindingRequirement;
import com.panfan.hivelineage.v2.ir.statement.BoundEffect;
import com.panfan.hivelineage.v2.ir.statement.VersionRef;
import com.panfan.hivelineage.v2.metadata.DefinitionContext;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.RelationSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SessionContractsTest {
    @Test
    public void initialSessionsCopyVariablesAndKeepTaskStateIsolated() {
        VariableKey key = new VariableKey(VariableKey.Namespace.HIVEVAR, "date");
        Map<VariableKey, VariableState> variables = new LinkedHashMap<>();
        variables.put(key, variable(VariableState.Resolution.KNOWN, "20261012"));
        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("caseSensitive", "false");
        InitialSessionContext initial = new InitialSessionContext("session-a", "wf", "task-a", "V1", "cluster", Optional.of(name("ods")), variables, configuration);
        SessionSnapshot first = SessionSnapshot.initial(initial);
        SessionSnapshot second = SessionSnapshot.initial(initial("session-b", "task-b", Collections.<VariableKey, VariableState>emptyMap()));
        variables.clear();
        configuration.clear();

        assertEquals("20261012", first.getVariables().get(key).getValue().get());
        assertEquals("false", initial.getSemanticConfiguration().get("caseSensitive"));
        assertTrue(second.getVariables().isEmpty());
        assertEquals(0L, first.getRevision());
        assertTrue(first.getTemporaryCatalog().isEmpty());
        assertTrue(first.getPersistentOverlay().isEmpty());
        assertTrue(first.getDatasetVersions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> {
            first.getVariables().clear();
        });
    }

    @Test
    public void variableNamespacesAndFallbackQualityRemainDistinct() {
        VariableKey hivevar = new VariableKey(VariableKey.Namespace.HIVEVAR, "date");
        VariableKey hiveconf = new VariableKey(VariableKey.Namespace.HIVECONF, "date");
        assertNotEquals(hivevar, hiveconf);
        assertEquals(hivevar, new VariableKey(VariableKey.Namespace.HIVEVAR, "date"));
        VariableState known = variable(VariableState.Resolution.KNOWN, "same");
        VariableState fallback = variable(VariableState.Resolution.FALLBACK, "same");
        assertEquals(known.getValue(), fallback.getValue());
        assertNotEquals(known.getResolution(), fallback.getResolution());
        VariableState unresolved = new VariableState(VariableState.Resolution.UNRESOLVED, Optional.<String>empty(), SourceRef.synthetic("script"));
        assertFalse(unresolved.getValue().isPresent());
        assertThrows(IllegalArgumentException.class, () -> {
            new VariableState(VariableState.Resolution.KNOWN, Optional.<String>empty(), SourceRef.synthetic("script"));
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new VariableState(VariableState.Resolution.UNRESOLVED, Optional.of("guess"), SourceRef.synthetic("script"));
        });
    }

    @Test
    public void snapshotKeepsTemporaryObjectsPermanentTombstonesAndLegacyDefinitionsSeparate() {
        QualifiedName tableName = name("source_db", "tmp_users");
        CatalogEntry temporary = catalog("temporary-id", tableName);
        LogicalDefinition legacy = new LogicalDefinition(LogicalDefinition.Kind.LEGACY_CTE, definition("cte"), Optional.<DefinitionTemplate>empty());
        Map<QualifiedName, CatalogEntry> temporaryCatalog = new LinkedHashMap<>();
        temporaryCatalog.put(tableName, temporary);
        Map<QualifiedName, OverlayEntry> permanent = new LinkedHashMap<>();
        permanent.put(tableName, OverlayEntry.deleted());
        Map<QualifiedName, LogicalDefinition> definitions = new LinkedHashMap<>();
        definitions.put(name("prior_cte"), legacy);
        SessionSnapshot snapshot = new SessionSnapshot(initial("s", "t", Collections.<VariableKey, VariableState>emptyMap()), Optional.of(name("ods")), Collections.<VariableKey, VariableState>emptyMap(), temporaryCatalog, permanent, definitions, Collections.<ObjectId, DataVersionState>emptyMap(), UncertaintyState.empty(), 3L);
        temporaryCatalog.clear();
        permanent.clear();
        definitions.clear();

        assertSame(temporary, snapshot.getTemporaryCatalog().get(tableName));
        assertEquals(OverlayEntry.State.DELETED, snapshot.getPersistentOverlay().get(tableName).getState());
        assertFalse(snapshot.getPersistentOverlay().get(tableName).getEntry().isPresent());
        assertEquals(LogicalDefinition.Kind.LEGACY_CTE, snapshot.getScriptDefinitions().get(name("prior_cte")).getKind());
        assertEquals(1, snapshot.getTemporaryCatalog().size());
        assertEquals(3L, snapshot.getRevision());
        assertThrows(UnsupportedOperationException.class, () -> {
            snapshot.getPersistentOverlay().clear();
        });
    }

    @Test
    public void logicalDefinitionRequiresMatchingStructuredTemplateIdentity() {
        DefinitionRef reference = definition("expected");
        DefinitionTemplate matching = template("expected");
        assertTrue(new LogicalDefinition(LogicalDefinition.Kind.VIEW, reference, Optional.of(matching)).getTemplate().isPresent());
        assertThrows(IllegalArgumentException.class, () -> {
            new LogicalDefinition(LogicalDefinition.Kind.VIEW, reference, Optional.of(template("different")));
        });
    }

    @Test
    public void versionRecordsDistinguishUnrequestedUnknownAndAssumedSuccess() {
        DataVersionState unrequested = new DataVersionState(VersionRef.notRequested(), Collections.<DatasetVersionId>emptyList(), DataVersionState.Basis.UNKNOWN);
        DataVersionState unknown = new DataVersionState(VersionRef.unknown("PREDECESSOR_FAILED"), Collections.<DatasetVersionId>emptyList(), DataVersionState.Basis.UNKNOWN);
        List<DatasetVersionId> parents = new ArrayList<>();
        parents.add(new DatasetVersionId("before"));
        DataVersionState known = new DataVersionState(VersionRef.known(new DatasetVersionId("after")), parents, DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS);
        parents.clear();
        assertEquals(VersionRef.State.NOT_REQUESTED, unrequested.getVersion().getState());
        assertEquals(VersionRef.State.UNKNOWN, unknown.getVersion().getState());
        assertEquals(DataVersionState.Basis.ASSUMED_PREDECESSOR_SUCCESS, known.getBasis());
        assertEquals(1, known.getPredecessors().size());
        assertThrows(IllegalArgumentException.class, () -> {
            new DataVersionState(VersionRef.notRequested(), Collections.singletonList(new DatasetVersionId("fake")), DataVersionState.Basis.UNKNOWN);
        });
    }

    @Test
    public void uncertaintyAndDefinitionDemandAreImmutableEvidence() {
        Set<ObjectId> objects = new LinkedHashSet<>();
        objects.add(new ObjectId("uncertain"));
        Set<VariableKey> variables = new LinkedHashSet<>();
        variables.add(new VariableKey(VariableKey.Namespace.HIVECONF, "date"));
        UncertaintyState uncertainty = new UncertaintyState(objects, variables, true, false, DiagnosticBag.empty());
        objects.clear();
        variables.clear();
        assertEquals(1, uncertainty.getObjects().size());
        assertEquals(1, uncertainty.getVariables().size());
        assertTrue(uncertainty.isCatalogUncertain());
        assertFalse(uncertainty.isDatabaseUncertain());

        Set<BindingRequirement> requirements = EnumSet.of(BindingRequirement.CONDITION_FIELDS);
        List<DefinitionId> path = new ArrayList<>();
        path.add(new DefinitionId("outer"));
        DefinitionRequest request = new DefinitionRequest(definition("inner"), requirements, path, 2);
        requirements.clear();
        path.clear();
        assertEquals(1, request.getRequirements().size());
        assertEquals(1, request.getExpansionPath().size());
        assertEquals(2, request.getRemainingDepth());
        assertThrows(UnsupportedOperationException.class, () -> {
            request.getExpansionPath().clear();
        });
    }

    @Test
    public void overlayAndPlanRejectContradictoryOrNegativeState() {
        CatalogEntry entry = catalog("permanent", name("dwd", "users"));
        assertEquals(OverlayEntry.State.UPSERTED, OverlayEntry.upserted(entry).getState());
        assertEquals(OverlayEntry.State.UNKNOWN, OverlayEntry.unknown(DiagnosticBag.empty()).getState());
        assertThrows(IllegalArgumentException.class, () -> {
            new OverlayEntry(OverlayEntry.State.DELETED, Optional.of(entry), DiagnosticBag.empty());
        });
        SessionEffectPlan plan = new SessionEffectPlan(2L, Collections.<BoundEffect>emptyList(), DiagnosticBag.empty());
        assertEquals(2L, plan.getExpectedRevision());
        assertTrue(plan.getEffects().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> {
            new SessionEffectPlan(-1L, Collections.<BoundEffect>emptyList(), DiagnosticBag.empty());
        });
    }

    @Test
    public void commitResultExplicitlyRetainsStateAfterRejection() {
        SessionSnapshot before = SessionSnapshot.initial(initial("s", "t", Collections.<VariableKey, VariableState>emptyMap()));
        SessionCommitResult result = new SessionCommitResult(CommitOutcome.STALE_REVISION, before, DiagnosticBag.empty());
        assertEquals(CommitOutcome.STALE_REVISION, result.getOutcome());
        assertSame(before, result.getSnapshot());
        assertEquals(0L, result.getSnapshot().getRevision());
        assertThrows(NullPointerException.class, () -> {
            new SessionCommitResult(CommitOutcome.REJECTED, null, DiagnosticBag.empty());
        });
    }

    private static VariableState variable(VariableState.Resolution resolution, String value) {
        return new VariableState(resolution, Optional.of(value), SourceRef.synthetic("script"));
    }

    private static InitialSessionContext initial(String session, String task, Map<VariableKey, VariableState> variables) {
        return new InitialSessionContext(session, "wf", task, "V1", "cluster", Optional.of(name("ods")), variables, Collections.<String, String>emptyMap());
    }

    private static CatalogEntry catalog(String id, QualifiedName name) {
        ObjectDescriptor object = new ObjectDescriptor(new ObjectId(id), name, ObjectDescriptor.Kind.TEMPORARY_TABLE, Optional.<DefinitionRef>empty(), provenance(), MetadataVersion.unknown());
        return new CatalogEntry(object, Collections.singletonList(name), Optional.<RelationSchema>empty(), Optional.<LogicalDefinition>empty());
    }

    private static DefinitionRef definition(String id) {
        MetadataReadContext read = new MetadataReadContext("metadata", "cluster", Optional.<String>empty(), Collections.<String, String>emptyMap());
        DefinitionContext context = new DefinitionContext(Optional.of(name("ods")), Collections.<String, String>emptyMap(), "hive", "4.0.1", "v2", read);
        return new DefinitionRef(new DefinitionId(id), Optional.of("select id from users"), context, provenance(), MetadataVersion.unknown(), SourceRef.synthetic(id));
    }

    private static MetadataProvenance provenance() {
        return new MetadataProvenance("metadata", MetadataProvenance.Origin.SESSION_DEFINED, Optional.<String>empty(), Optional.<Long>empty());
    }

    private static QualifiedName name(String... parts) {
        return new QualifiedName(Arrays.asList(parts));
    }

    private static DefinitionTemplate template(final String id) {
        return new DefinitionTemplate() {
            @Override
            public DefinitionId getDefinitionId() {
                return new DefinitionId(id);
            }

            @Override
            public SourceRef getSource() {
                return SourceRef.synthetic(id);
            }
        };
    }
}
