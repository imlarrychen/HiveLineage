package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MetadataImplementationsTest {
    private final MetadataReadContext context = scope("source-a");
    private final QualifiedName name = new QualifiedName(Arrays.asList("db", "table"));

    @Test
    public void adapterPreservesOrderedColumnsAndViewContext() {
        HiveMetadataProvider original = table -> HiveTableMetadata.view(table, Arrays.asList("b", "a"), "select b, a from base");
        MetadataProvider provider = new DefaultV1MetadataAdapterFactory().adapt(binding(original, new HiveMetadataCache(), context));
        RelationMetadata value = provider.lookupRelation(name, context).getValue().get();
        assertEquals("b", value.getSchema().getColumns().get(0).getName());
        assertEquals("a", value.getSchema().getColumns().get(1).getName());
        assertEquals(ObjectDescriptor.Kind.VIEW, value.getObject().getKind());
        assertEquals("db", value.getObject().getDefinition().get().getDefinitionContext().getDefaultDatabase().get().getDisplayName());
        assertEquals(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, provider.getCapabilities().getLookupShape());
    }

    @Test
    public void missingAndAccessFailureStayDistinct() {
        HiveMetadataProvider missing = table -> null;
        MetadataProvider provider = new V1MetadataProviderAdapter(binding(missing, new HiveMetadataCache(), context));
        assertEquals(LookupStatus.UNKNOWN, provider.lookupRelation(name, context).getStatus());
        MetadataProvider authoritative = new V1MetadataProviderAdapter(new LegacyMetadataBinding(Optional.of(missing),
                Optional.<HiveMetadataCache>empty(), context, true));
        assertEquals(LookupStatus.NOT_FOUND, authoritative.lookupRelation(name, context).getStatus());
        HiveMetadataProvider failure = table -> {
            throw new IllegalStateException("offline");
        };
        MetadataProvider failed = new V1MetadataProviderAdapter(binding(failure, new HiveMetadataCache(), context));
        assertEquals(LookupStatus.ACCESS_ERROR, failed.lookupRelation(name, context).getStatus());
    }

    @Test(expected = IllegalArgumentException.class)
    public void legacyCacheCannotCrossProviderScopes() {
        HiveMetadataCache cache = new HiveMetadataCache();
        HiveMetadataProvider original = table -> HiveTableMetadata.table(table, Collections.singletonList("id"));
        new V1MetadataProviderAdapter(binding(original, cache, context));
        new V1MetadataProviderAdapter(binding(original, cache, scope("source-b")));
    }

    @Test
    public void facetLookupDoesNotLoadBundledSchema() {
        AtomicInteger schemaCalls = new AtomicInteger();
        ObjectDescriptorProvider light = new ObjectDescriptorProvider() {
            @Override
            public ProviderCapabilities getCapabilities() {
                return new ProviderCapabilities(EnumSet.of(MetadataFacet.OBJECT_DESCRIPTOR), ProviderCapabilities.LookupShape.FACET_LOOKUP, false);
            }

            @Override
            public LookupResult<ObjectDescriptor> lookupObject(QualifiedName requested, MetadataReadContext scope) {
                return LookupResult.found(object(requested));
            }
        };
        MetadataProvider bundled = provider(schemaCalls);
        WorkflowContext workflow = new WorkflowContext("workflow", context, bundled, new DefaultMetadataCache(),
                Optional.of(light), Optional.<RelationSchemaProvider>empty());
        CapturedMetadataAccess access = new DefaultMetadataAccessFactory().create(workflow);
        assertEquals(LookupStatus.FOUND, access.lookupObject(name, context).getStatus());
        assertEquals(0, schemaCalls.get());
        assertEquals(1, access.manifest().getRecords().size());
        assertEquals(ProviderCapabilities.LookupShape.FACET_LOOKUP, access.manifest().getRecords().get(0).getLookupShape());
    }

    @Test
    public void concurrentBundledReadsMergeAndSeedAllFacets() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapturedMetadataAccess access = new DefaultMetadataAccessFactory().create(new WorkflowContext("workflow", context,
                provider(calls), new DefaultMetadataCache()));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    access.lookupObject(name, context);
                    access.lookupSchema(name, context);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            assertEquals(1, calls.get());
            assertEquals(3, access.snapshot().getLookups().size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void cacheExpiryCannotChangePinnedBatchAndScopesDoNotCollide() {
        AtomicLong clock = new AtomicLong(100L);
        DefaultMetadataCache cache = new DefaultMetadataCache(clock::get);
        MetadataCacheKey key = new MetadataCacheKey(context, name, MetadataFacet.OBJECT_DESCRIPTOR);
        cache.put(key, ObjectDescriptor.class, LookupResult.found(object(name)), 10L);
        assertTrue(cache.get(key, ObjectDescriptor.class).isPresent());
        assertFalse(cache.get(new MetadataCacheKey(scope("other"), name, MetadataFacet.OBJECT_DESCRIPTOR), ObjectDescriptor.class).isPresent());
        clock.set(111L);
        assertFalse(cache.get(key, ObjectDescriptor.class).isPresent());
        AtomicInteger calls = new AtomicInteger();
        CapturedMetadataAccess access = new DefaultMetadataAccessFactory(10L).create(new WorkflowContext("workflow", context, provider(calls), cache));
        RelationMetadata first = access.lookupRelation(name, context).getValue().get();
        clock.addAndGet(1000L);
        assertTrue(first == access.lookupRelation(name, context).getValue().get());
        assertEquals(1, calls.get());
    }

    @Test
    public void replayNeverFallsBackToLiveProvider() {
        CapturedMetadataAccess access = new DefaultMetadataAccessFactory().create(new WorkflowContext("workflow", context,
                provider(new AtomicInteger()), new DefaultMetadataCache()));
        access.lookupRelation(name, context);
        MetadataAccess replay = new DefaultMetadataReplayProviderFactory().create(access.snapshot());
        assertEquals(LookupStatus.FOUND, replay.lookupSchema(name, context).getStatus());
        assertEquals(LookupStatus.UNKNOWN, replay.lookupObject(new QualifiedName(Arrays.asList("db", "absent")), context).getStatus());
    }

    @Test
    public void errorsArePinnedForBatchButNotProcessNegativeCache() {
        DefaultMetadataCache cache = new DefaultMetadataCache();
        MetadataCacheKey key = new MetadataCacheKey(context, name, MetadataFacet.OBJECT_DESCRIPTOR);
        cache.put(key, ObjectDescriptor.class, LookupResult.accessError(DiagnosticBag.empty()), 0L);
        assertFalse(cache.get(key, ObjectDescriptor.class).isPresent());
        cache.put(key, ObjectDescriptor.class, LookupResult.notFound(DiagnosticBag.empty()), 0L);
        assertEquals(LookupStatus.NOT_FOUND, cache.get(key, ObjectDescriptor.class).get().getStatus());
    }

    @Test
    public void processCacheRetainsBundledLookupOriginInNewBatchAndSnapshot() {
        DefaultMetadataCache cache = new DefaultMetadataCache();
        AtomicInteger calls = new AtomicInteger();
        MetadataProvider provider = provider(calls);
        CapturedMetadataAccess first = new CapturedMetadataAccess(new WorkflowContext("workflow", context, provider, cache), 0L);
        first.lookupObject(name, context);
        CapturedMetadataAccess second = new CapturedMetadataAccess(new WorkflowContext("workflow", context, provider, cache), 0L);
        second.lookupObject(name, context);
        assertEquals(1, calls.get());
        assertEquals(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, second.manifest().getRecords().get(0).getLookupShape());
        assertEquals(Optional.of(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP), second.snapshot().getLookups().get(0).getLookupShape());
    }

    @Test
    public void fingerprintDistinguishesColumnListDelimiters() {
        HiveMetadataProvider first = table -> HiveTableMetadata.table(table, Collections.singletonList("a, b"));
        HiveMetadataProvider second = table -> HiveTableMetadata.table(table, Arrays.asList("a", "b"));
        RelationMetadata left = new V1MetadataProviderAdapter(binding(first, new HiveMetadataCache(), context)).lookupRelation(name, context).getValue().get();
        RelationMetadata right = new V1MetadataProviderAdapter(binding(second, new HiveMetadataCache(), context)).lookupRelation(name, context).getValue().get();
        assertFalse(left.getObject().getProvenance().getContentFingerprint().equals(right.getObject().getProvenance().getContentFingerprint()));
    }

    @Test
    public void unrecordedCacheShapeCannotClaimARealFacetLookup() {
        DefaultMetadataCache cache = new DefaultMetadataCache();
        cache.put(new MetadataCacheKey(context, name, MetadataFacet.OBJECT_DESCRIPTOR), ObjectDescriptor.class,
                LookupResult.found(object(name)), 0L);
        AtomicInteger calls = new AtomicInteger();
        CapturedMetadataAccess access = new CapturedMetadataAccess(new WorkflowContext("workflow", context, provider(calls), cache), 0L);
        access.lookupObject(name, context);
        assertEquals(0, calls.get());
        assertEquals(ProviderCapabilities.LookupShape.UNKNOWN, access.manifest().getRecords().get(0).getLookupShape());
        SnapshotMetadataServices replay = new SnapshotMetadataServices(access.snapshot());
        assertEquals(ProviderCapabilities.LookupShape.UNKNOWN, replay.getLookupShape(name, MetadataFacet.OBJECT_DESCRIPTOR));
    }

    @Test
    public void replacingCacheEntryCannotMixPayloadAndReadOrigin() {
        AtomicReference<Runnable> onRead = new AtomicReference<>();
        DefaultMetadataCache cache = new DefaultMetadataCache(() -> {
            Runnable replacement = onRead.getAndSet(null);
            if (replacement != null) {
                replacement.run();
            }
            return 100L;
        });
        MetadataCacheKey key = new MetadataCacheKey(context, name, MetadataFacet.OBJECT_DESCRIPTOR);
        ObjectDescriptor old = object(name);
        cache.put(key, ObjectDescriptor.class, LookupResult.found(old), 1000L, ProviderCapabilities.LookupShape.BUNDLED_LOOKUP);
        onRead.set(() -> cache.put(key, ObjectDescriptor.class, LookupResult.found(object(new QualifiedName(Arrays.asList("db", "new")))),
                1000L, ProviderCapabilities.LookupShape.FACET_LOOKUP));
        CapturedMetadataAccess access = new CapturedMetadataAccess(new WorkflowContext("workflow", context, provider(new AtomicInteger()), cache), 0L);
        assertTrue(old == access.lookupObject(name, context).getValue().get());
        assertEquals(ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, access.manifest().getRecords().get(0).getLookupShape());
    }

    private MetadataProvider provider(AtomicInteger calls) {
        HiveMetadataProvider original = table -> {
            calls.incrementAndGet();
            return HiveTableMetadata.table(table, Arrays.asList("id", "name"));
        };
        return new V1MetadataProviderAdapter(new LegacyMetadataBinding(Optional.of(original), Optional.<HiveMetadataCache>empty(), context, false));
    }

    private ObjectDescriptor object(QualifiedName requested) {
        return new ObjectDescriptor(new ObjectId("object"), requested, ObjectDescriptor.Kind.TABLE, Optional.<DefinitionRef>empty(),
                new MetadataProvenance("source-a", MetadataProvenance.Origin.TEST_FIXTURE, Optional.<String>empty(), Optional.<Long>empty()), MetadataVersion.unknown());
    }

    private static LegacyMetadataBinding binding(HiveMetadataProvider provider, HiveMetadataCache cache, MetadataReadContext scope) {
        return new LegacyMetadataBinding(Optional.of(provider), Optional.of(cache), scope, false);
    }

    private static MetadataReadContext scope(String source) {
        return new MetadataReadContext(source, "cluster", Optional.<String>empty(), Collections.<String, String>emptyMap());
    }
}
