package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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

public class MetadataContractsTest {
    @Test
    public void absentMetadataStatesRemainDistinctAndRetainDiagnostics() {
        Diagnostic diagnostic = new Diagnostic(AnalysisStage.BINDING, "READ_FAILED", "read failed", null);
        DiagnosticBag bag = new DiagnosticBag(Collections.singletonList(diagnostic));
        List<LookupResult<String>> outcomes = Arrays.asList(
            LookupResult.<String>unknown(bag),
            LookupResult.<String>notFound(bag),
            LookupResult.<String>capabilityMissing(bag),
            LookupResult.<String>accessError(bag)
        );
        List<LookupStatus> expected = Arrays.asList(
            LookupStatus.UNKNOWN, LookupStatus.NOT_FOUND, LookupStatus.CAPABILITY_MISSING, LookupStatus.ACCESS_ERROR
        );
        for (int index = 0; index < outcomes.size(); index++) {
            assertEquals(expected.get(index), outcomes.get(index).getStatus());
            assertFalse(outcomes.get(index).getValue().isPresent());
            assertSame(bag, outcomes.get(index).getDiagnostics());
        }
        assertEquals(LookupStatus.FOUND, LookupResult.found("value").getStatus());
        assertEquals(Optional.of("value"), LookupResult.found("value").getValue());
    }

    @Test
    public void resultRejectsNullOrContradictoryPayloads() {
        assertThrows(NullPointerException.class, () -> {
            LookupResult.found(null);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new LookupResult<String>(LookupStatus.FOUND, Optional.<String>empty(), DiagnosticBag.empty());
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new LookupResult<String>(LookupStatus.UNKNOWN, Optional.of("value"), DiagnosticBag.empty());
        });
    }

    @Test
    public void keySeparatesSourceAccessSnapshotAndFacetButNotTaskVersion() {
        QualifiedName name = name("ods", "users");
        MetadataCacheKey key = new MetadataCacheKey(context("service-a", "reader", "s1"), name, MetadataFacet.RELATION_SCHEMA);
        assertEquals(key, new MetadataCacheKey(context("service-a", "reader", "s1"), name, MetadataFacet.RELATION_SCHEMA));
        assertEquals(key.hashCode(), new MetadataCacheKey(context("service-a", "reader", "s1"), name, MetadataFacet.RELATION_SCHEMA).hashCode());
        assertNotEquals(key, new MetadataCacheKey(context("service-b", "reader", "s1"), name, MetadataFacet.RELATION_SCHEMA));
        assertNotEquals(key, new MetadataCacheKey(context("service-a", "writer", "s1"), name, MetadataFacet.RELATION_SCHEMA));
        assertNotEquals(key, new MetadataCacheKey(context("service-a", "reader", "s2"), name, MetadataFacet.RELATION_SCHEMA));
        assertNotEquals(key, new MetadataCacheKey(context("service-a", "reader", "s1"), name, MetadataFacet.OBJECT_DESCRIPTOR));
        for (Field field : MetadataReadContext.class.getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase().contains("taskversion"));
        }
        for (Field field : MetadataCacheKey.class.getDeclaredFields()) {
            assertFalse(field.getName().toLowerCase().contains("taskversion"));
        }
    }

    @Test
    public void contextAndCapabilityCollectionsAreDefensiveAndBundledLoadingIsExplicit() {
        Map<String, String> access = new LinkedHashMap<>();
        access.put("role", "reader");
        MetadataReadContext context = new MetadataReadContext("source", "cluster", Optional.<String>empty(), access);
        Set<MetadataFacet> facets = EnumSet.of(MetadataFacet.RELATION_METADATA);
        ProviderCapabilities capabilities = new ProviderCapabilities(facets, ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, false);
        access.put("role", "writer");
        facets.add(MetadataFacet.OBJECT_DESCRIPTOR);

        assertEquals("reader", context.getAccessContext().get("role"));
        assertFalse(capabilities.supports(MetadataFacet.OBJECT_DESCRIPTOR));
        assertEquals(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, capabilities.getLookupShape());
        assertFalse(capabilities.isAuthoritativeNotFound());
        assertThrows(UnsupportedOperationException.class, () -> {
            context.getAccessContext().put("new", "value");
        });
        assertThrows(UnsupportedOperationException.class, () -> {
            capabilities.getFacets().add(MetadataFacet.DEFINITION);
        });
    }

    @Test
    public void orderedColumnsKeepUnknownAttributesWithoutNameDeduplication() {
        List<ColumnMetadata> columns = new ArrayList<>();
        columns.add(column("id", 0));
        columns.add(column("id", 1));
        RelationSchema schema = new RelationSchema(SchemaState.KNOWN, columns, provenance(), MetadataVersion.unknown());
        columns.clear();

        assertEquals(2, schema.getColumns().size());
        assertEquals(0, schema.getColumns().get(0).getPosition());
        assertEquals(1, schema.getColumns().get(1).getPosition());
        assertEquals(TypeRef.Kind.UNKNOWN, schema.getColumns().get(0).getType().getKind());
        assertEquals(ColumnMetadata.Role.UNKNOWN, schema.getColumns().get(0).getRole());
        assertEquals(ColumnMetadata.Nullability.UNKNOWN, schema.getColumns().get(0).getNullability());
        assertEquals(MetadataVersion.State.UNKNOWN, schema.getVersion().getState());
        assertThrows(UnsupportedOperationException.class, () -> {
            schema.getColumns().clear();
        });
    }

    @Test
    public void knownEmptySchemaIsDistinctFromUnrequestedAndUnknown() {
        RelationSchema known = new RelationSchema(SchemaState.KNOWN, Collections.<ColumnMetadata>emptyList(), provenance(), MetadataVersion.unknown());
        RelationSchema unknown = new RelationSchema(SchemaState.UNKNOWN, Collections.<ColumnMetadata>emptyList(), provenance(), MetadataVersion.unknown());
        RelationSchema unrequested = new RelationSchema(SchemaState.NOT_REQUESTED, Collections.<ColumnMetadata>emptyList(), provenance(), MetadataVersion.notRequested());
        assertNotEquals(known.getState(), unknown.getState());
        assertNotEquals(unknown.getState(), unrequested.getState());
        assertThrows(IllegalArgumentException.class, () -> {
            new RelationSchema(SchemaState.NOT_REQUESTED, Collections.singletonList(column("id", 0)), provenance(), MetadataVersion.notRequested());
        });
    }

    @Test
    public void viewDefinitionPreservesContextOriginAndMissingVersion() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("hivevar:date", "20261012");
        DefinitionContext definitionContext = new DefinitionContext(Optional.of(name("ods")), variables, "hive", "4.0.1", "v2", context("service", "reader", "s1"));
        DefinitionRef definition = new DefinitionRef(new DefinitionId("view-definition"), Optional.of("select id from users"), definitionContext, provenance(), MetadataVersion.unknown(), SourceRef.synthetic("view.sql"));
        ObjectDescriptor object = new ObjectDescriptor(new ObjectId("view"), name("dwd", "users_view"), ObjectDescriptor.Kind.VIEW, Optional.of(definition), provenance(), MetadataVersion.unknown());
        variables.clear();

        assertEquals(Optional.of(name("ods")), definition.getDefinitionContext().getDefaultDatabase());
        assertEquals("20261012", definition.getDefinitionContext().getVariables().get("hivevar:date"));
        assertEquals(MetadataProvenance.Origin.PROVIDER_DEFINED, definition.getProvenance().getOrigin());
        assertFalse(definition.getVersion().getValue().isPresent());
        assertEquals(ObjectDescriptor.Kind.VIEW, object.getKind());
        assertEquals(Optional.of(definition), object.getDefinition());
    }

    @Test
    public void legacyBindingAllowsAbsentProviderAndDoesNotDeclareAuthoritativeNullByDefault() {
        LegacyMetadataBinding binding = new LegacyMetadataBinding(Optional.<HiveMetadataProvider>empty(), Optional.<HiveMetadataCache>empty(), context("legacy", "reader", "s1"), false);
        assertFalse(binding.getProvider().isPresent());
        assertFalse(binding.getCache().isPresent());
        assertFalse(binding.isAuthoritativeNullMeansNotFound());
        assertTrue(binding.getReadContext().getSnapshotId().isPresent());
    }

    @Test
    public void manifestRetainsReadVersionAndAccessFailureWithoutClaimingGlobalSnapshot() {
        MetadataReadContext context = new MetadataReadContext("service", "cluster", Optional.<String>empty(), Collections.<String, String>emptyMap());
        MetadataReadRecord record = new MetadataReadRecord(
            new MetadataCacheKey(context, name("ods", "users"), MetadataFacet.RELATION_SCHEMA),
            LookupStatus.ACCESS_ERROR, provenance(), ProviderCapabilities.LookupShape.BUNDLED_LOOKUP,
            MetadataVersion.unknown(), Optional.of("attempt-1")
        );
        List<MetadataReadRecord> records = new ArrayList<>();
        records.add(record);
        MetadataReadManifest manifest = new MetadataReadManifest(context, records, MetadataReadManifest.Consistency.PER_OBJECT_CAPTURE);
        records.clear();
        assertEquals(1, manifest.getRecords().size());
        assertEquals(LookupStatus.ACCESS_ERROR, manifest.getRecords().get(0).getStatus());
        assertEquals(MetadataVersion.State.UNKNOWN, manifest.getRecords().get(0).getMetadataVersion().getState());
        assertEquals(Optional.of("attempt-1"), manifest.getRecords().get(0).getReadId());
        assertThrows(IllegalArgumentException.class, () -> {
            new MetadataReadManifest(context, Collections.singletonList(record), MetadataReadManifest.Consistency.PROVIDER_SNAPSHOT);
        });
        assertThrows(UnsupportedOperationException.class, () -> {
            manifest.getRecords().clear();
        });
    }

    @Test
    public void absentWorkflowProviderAndOptionalFacetServicesNeedNoLookup() {
        MetadataCache cache = new MetadataCache() {
            @Override
            public <T> Optional<LookupResult<T>> get(MetadataCacheKey key, Class<T> payloadType) {
                throw new AssertionError("data construction must not read the cache");
            }

            @Override
            public <T> void put(MetadataCacheKey key, Class<T> payloadType, LookupResult<T> result, long ttlMillis) {
                throw new AssertionError("data construction must not write the cache");
            }
        };
        WorkflowContext workflow = new WorkflowContext("wf", context("service", "reader", "s1"),
            Optional.<MetadataProvider>empty(), cache, Optional.<ObjectDescriptorProvider>empty(),
            Optional.<RelationSchemaProvider>empty());
        assertFalse(workflow.getMetadataProvider().isPresent());
        assertFalse(workflow.getObjectDescriptorProvider().isPresent());
        assertFalse(workflow.getRelationSchemaProvider().isPresent());
        assertSame(cache, workflow.getMetadataCache());
    }

    @Test
    public void snapshotRetainsActualSchemaPayloadAndDefensiveContainers() {
        MetadataReadContext readContext = context("service", "reader", "s1");
        List<ColumnMetadata> columns = new ArrayList<>();
        columns.add(column("id", 0));
        RelationSchema schema = new RelationSchema(SchemaState.KNOWN, columns, provenance(), MetadataVersion.known("schema-1"));
        RecordedLookup<RelationSchema> lookup = new RecordedLookup<>(
            new MetadataCacheKey(readContext, name("ods", "users"), MetadataFacet.RELATION_SCHEMA),
            RelationSchema.class, LookupResult.found(schema)
        );
        List<RecordedLookup<?>> lookups = new ArrayList<>();
        lookups.add(lookup);
        MetadataSnapshot snapshot = new MetadataSnapshot(readContext, lookups);
        columns.clear();
        lookups.clear();

        assertEquals(1, snapshot.getLookups().size());
        assertSame(schema, snapshot.getLookups().get(0).getResult().getValue().get());
        assertEquals(RelationSchema.class, snapshot.getLookups().get(0).getPayloadType());
        assertEquals(1, lookup.getResult().getValue().get().getColumns().size());
        assertEquals(Optional.of("schema-1"), lookup.getResult().getValue().get().getVersion().getValue());
        assertThrows(UnsupportedOperationException.class, () -> {
            snapshot.getLookups().clear();
        });
    }

    @Test
    public void snapshotRejectsMixedSourcesAndAccessContexts() {
        MetadataReadContext readContext = context("service", "reader", "s1");
        RecordedLookup<RelationSchema> lookup = new RecordedLookup<>(
            new MetadataCacheKey(context("service", "admin", "s1"), name("ods", "users"), MetadataFacet.RELATION_SCHEMA),
            RelationSchema.class, LookupResult.<RelationSchema>accessError(DiagnosticBag.empty())
        );
        assertEquals(LookupStatus.ACCESS_ERROR, lookup.getResult().getStatus());
        assertThrows(IllegalArgumentException.class, () -> {
            new MetadataSnapshot(readContext, Collections.<RecordedLookup<?>>singletonList(lookup));
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new MetadataSnapshot(context("different-source", "admin", "s1"), Collections.<RecordedLookup<?>>singletonList(lookup));
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void recordedFoundPayloadMustMatchDeclaredRuntimeShape() {
        MetadataCacheKey key = new MetadataCacheKey(context("service", "reader", "s1"), name("ods", "users"), MetadataFacet.RELATION_SCHEMA);
        ObjectDescriptor wrongPayload = new ObjectDescriptor(new ObjectId("users"), name("ods", "users"),
            ObjectDescriptor.Kind.TABLE, Optional.<DefinitionRef>empty(), provenance(), MetadataVersion.unknown());
        assertThrows(IllegalArgumentException.class, () -> {
            new RecordedLookup(key, RelationSchema.class, LookupResult.found(wrongPayload));
        });
        RecordedLookup<RelationSchema> unknown = new RecordedLookup<>(key, RelationSchema.class,
            LookupResult.<RelationSchema>unknown(DiagnosticBag.empty()));
        assertEquals(LookupStatus.UNKNOWN, unknown.getResult().getStatus());
        assertFalse(unknown.getResult().getValue().isPresent());
    }

    private static ColumnMetadata column(String name, int position) {
        return new ColumnMetadata(name, position, TypeRef.unknown(), ColumnMetadata.Role.UNKNOWN, ColumnMetadata.Nullability.UNKNOWN);
    }

    private static QualifiedName name(String... parts) {
        return new QualifiedName(Arrays.asList(parts));
    }

    private static MetadataReadContext context(String source, String role, String snapshot) {
        return new MetadataReadContext(source, "cluster", Optional.of(snapshot), Collections.singletonMap("role", role));
    }

    private static MetadataProvenance provenance() {
        return new MetadataProvenance("service", MetadataProvenance.Origin.PROVIDER_DEFINED, Optional.<String>empty(), Optional.<Long>empty());
    }
}
