package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Serializes only reads in a batch. Payloads, failures and negative results are pinned. */
public final class CapturedMetadataAccess implements WorkflowMetadataAccess {
    private final MetadataReadContext context;
    private final MetadataCache cache;
    private final Optional<MetadataProvider> provider;
    private final Optional<ObjectDescriptorProvider> objectProvider;
    private final Optional<RelationSchemaProvider> schemaProvider;
    private final long ttlMillis;
    private final Map<MetadataCacheKey, RecordedLookup<?>> pinned = new LinkedHashMap<>();
    private final Map<MetadataCacheKey, ProviderCapabilities.LookupShape> shapes = new LinkedHashMap<>();

    public CapturedMetadataAccess(WorkflowContext workflow, long ttlMillis) {
        context = workflow.getReadContext();
        cache = workflow.getMetadataCache();
        provider = workflow.getMetadataProvider();
        objectProvider = workflow.getObjectDescriptorProvider();
        schemaProvider = workflow.getRelationSchemaProvider();
        this.ttlMillis = ttlMillis;
    }

    @Override
    public synchronized LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext scope) {
        return lookupObject(name, scope, null);
    }

    public synchronized LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext scope, ExternalReadBudget budget) {
        return load(name, scope, MetadataFacet.OBJECT_DESCRIPTOR, ObjectDescriptor.class, () -> {
            if (objectProvider.isPresent() && objectProvider.get().getCapabilities().supports(MetadataFacet.OBJECT_DESCRIPTOR)) {
                shapes.put(key(name, MetadataFacet.OBJECT_DESCRIPTOR), objectProvider.get().getLookupShape(name, MetadataFacet.OBJECT_DESCRIPTOR));
                consume(budget);
                return objectProvider.get().lookupObject(name, scope);
            }
            LookupResult<RelationMetadata> relation = lookupRelation(name, scope, budget);
            shapes.put(key(name, MetadataFacet.OBJECT_DESCRIPTOR), shape(name, MetadataFacet.RELATION_METADATA));
            return convert(relation, relation.getValue().map(RelationMetadata::getObject));
        });
    }

    @Override
    public synchronized LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext scope) {
        return lookupSchema(name, scope, null);
    }

    public synchronized LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext scope, ExternalReadBudget budget) {
        return load(name, scope, MetadataFacet.RELATION_SCHEMA, RelationSchema.class, () -> {
            if (schemaProvider.isPresent() && schemaProvider.get().getCapabilities().supports(MetadataFacet.RELATION_SCHEMA)) {
                shapes.put(key(name, MetadataFacet.RELATION_SCHEMA), schemaProvider.get().getLookupShape(name, MetadataFacet.RELATION_SCHEMA));
                consume(budget);
                return schemaProvider.get().lookupSchema(name, scope);
            }
            LookupResult<RelationMetadata> relation = lookupRelation(name, scope, budget);
            shapes.put(key(name, MetadataFacet.RELATION_SCHEMA), shape(name, MetadataFacet.RELATION_METADATA));
            return convert(relation, relation.getValue().map(RelationMetadata::getSchema));
        });
    }

    @Override
    public synchronized LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext scope) {
        return lookupRelation(name, scope, null);
    }

    public synchronized LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext scope, ExternalReadBudget budget) {
        return load(name, scope, MetadataFacet.RELATION_METADATA, RelationMetadata.class, () -> {
            if (provider.isPresent()) {
                shapes.put(key(name, MetadataFacet.RELATION_METADATA), provider.get().getLookupShape(name, MetadataFacet.RELATION_METADATA));
                consume(budget);
                LookupResult<RelationMetadata> result = provider.get().lookupRelation(name, scope);
                if (result.getValue().isPresent()) {
                    RelationMetadata relation = result.getValue().get();
                    seed(name, MetadataFacet.OBJECT_DESCRIPTOR, ObjectDescriptor.class,
                        LookupResult.found(relation.getObject(), result.getDiagnostics()), provider.get().getLookupShape(name, MetadataFacet.RELATION_METADATA));
                    seed(name, MetadataFacet.RELATION_SCHEMA, RelationSchema.class,
                        LookupResult.found(relation.getSchema(), result.getDiagnostics()), provider.get().getLookupShape(name, MetadataFacet.RELATION_METADATA));
                }
                return result;
            }
            if (objectProvider.isPresent() && schemaProvider.isPresent()
                && objectProvider.get().getCapabilities().supports(MetadataFacet.OBJECT_DESCRIPTOR)
                && schemaProvider.get().getCapabilities().supports(MetadataFacet.RELATION_SCHEMA)) {
                LookupResult<ObjectDescriptor> object = lookupObject(name, scope, budget);
                shapes.put(key(name, MetadataFacet.RELATION_METADATA), ProviderCapabilities.LookupShape.FACET_LOOKUP);
                if (!object.getValue().isPresent()) {
                    return convert(object, Optional.<RelationMetadata>empty());
                }
                LookupResult<RelationSchema> schema = lookupSchema(name, scope, budget);
                return convert(schema, schema.getValue().map(value -> new RelationMetadata(object.getValue().get(), value)));
            }
            return LookupResult.capabilityMissing(DiagnosticBag.empty());
        });
    }

    public synchronized MetadataSnapshot snapshot() {
        return new MetadataSnapshot(context, new ArrayList<>(pinned.values()));
    }

    public synchronized MetadataReadManifest manifest() {
        List<MetadataReadRecord> records = new ArrayList<>();
        for (RecordedLookup<?> read : pinned.values()) {
            Object payload = read.getResult().getValue().orElse(null);
            MetadataProvenance provenance = new MetadataProvenance(context.getSourceId(),
                MetadataProvenance.Origin.PROVIDER_DEFINED, Optional.<String>empty(), Optional.<Long>empty());
            MetadataVersion version = MetadataVersion.unknown();
            if (payload instanceof RelationMetadata) {
                provenance = ((RelationMetadata) payload).getObject().getProvenance();
                version = ((RelationMetadata) payload).getObject().getVersion();
            } else if (payload instanceof ObjectDescriptor) {
                provenance = ((ObjectDescriptor) payload).getProvenance();
                version = ((ObjectDescriptor) payload).getVersion();
            } else if (payload instanceof RelationSchema) {
                provenance = ((RelationSchema) payload).getProvenance();
                version = ((RelationSchema) payload).getVersion();
            }
            records.add(new MetadataReadRecord(read.getKey(), read.getResult().getStatus(), provenance,
                shapes.getOrDefault(read.getKey(), ProviderCapabilities.LookupShape.FACET_LOOKUP), version, Optional.<String>empty()));
        }
        return new MetadataReadManifest(context, records, context.getSnapshotId().isPresent()
            ? MetadataReadManifest.Consistency.PROVIDER_SNAPSHOT : MetadataReadManifest.Consistency.PER_OBJECT_CAPTURE);
    }

    private static void consume(ExternalReadBudget budget) {
        if (budget != null) {
            budget.beforeRead();
        }
    }

    private MetadataCacheKey key(QualifiedName name, MetadataFacet facet) {
        return new MetadataCacheKey(context, name, facet);
    }

    private ProviderCapabilities.LookupShape shape(QualifiedName name, MetadataFacet facet) {
        MetadataCacheKey key = key(name, facet);
        return shapes.getOrDefault(key, cache.getLookupShape(key).orElseGet(() -> inferredShape(name, facet)));
    }

    private ProviderCapabilities.LookupShape inferredShape(QualifiedName name, MetadataFacet facet) {
        if (facet == MetadataFacet.OBJECT_DESCRIPTOR && objectProvider.isPresent()) {
            return objectProvider.get().getLookupShape(name, facet);
        }
        if (facet == MetadataFacet.RELATION_SCHEMA && schemaProvider.isPresent()) {
            return schemaProvider.get().getLookupShape(name, facet);
        }
        return provider.map(value -> value.getLookupShape(name, facet)).orElse(ProviderCapabilities.LookupShape.FACET_LOOKUP);
    }

    @SuppressWarnings("unchecked")
    private <T> LookupResult<T> load(QualifiedName name, MetadataReadContext scope, MetadataFacet facet,
                                    Class<T> type, Supplier<LookupResult<T>> reader) {
        if (!context.equals(scope)) {
            throw new IllegalArgumentException("workflow read context mismatch");
        }
        MetadataCacheKey key = key(name, facet);
        RecordedLookup<?> prior = pinned.get(key);
        if (prior != null) {
            if (!prior.getPayloadType().equals(type)) {
                throw new IllegalArgumentException("pinned payload type mismatch");
            }
            return (LookupResult<T>) prior.getResult();
        }
        Optional<RecordedLookup<T>> cached = cache.getRecordedLookup(key, type);
        LookupResult<T> result;
        if (cached.isPresent()) {
            result = cached.get().getResult();
            shapes.put(key, cached.get().getLookupShape().orElse(ProviderCapabilities.LookupShape.UNKNOWN));
        } else {
            try {
                result = java.util.Objects.requireNonNull(reader.get(), "provider returned null lookup");
            } catch (RuntimeException failure) {
                if (failure instanceof ResourceLimitException) {
                    throw failure;
                }
                Diagnostic diagnostic = new Diagnostic(AnalysisStage.METADATA, "METADATA_ACCESS_ERROR",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), SourceRef.synthetic("metadata"));
                result = LookupResult.accessError(new DiagnosticBag(Collections.singletonList(diagnostic)));
            }
            cache.put(key, type, result, ttlMillis, shape(name, facet));
        }
        pinned.putIfAbsent(key, new RecordedLookup<>(key, type, result, shape(name, facet)));
        return (LookupResult<T>) pinned.get(key).getResult();
    }

    private <T> void seed(QualifiedName name, MetadataFacet facet, Class<T> type,
                          LookupResult<T> result, ProviderCapabilities.LookupShape shape) {
        MetadataCacheKey key = key(name, facet);
        pinned.putIfAbsent(key, new RecordedLookup<>(key, type, result, shape));
        shapes.putIfAbsent(key, shape);
        cache.put(key, type, result, ttlMillis, shape);
    }

    private static <A, B> LookupResult<B> convert(LookupResult<A> result, Optional<B> value) {
        return new LookupResult<>(result.getStatus(), value, result.getDiagnostics());
    }
}
