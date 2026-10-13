package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.SourceRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Collections;
import java.util.Optional;
import java.util.Map;
import java.util.LinkedHashMap;

/** Read-only faceted and legacy views of one captured source; no live fallback. */
public final class SnapshotMetadataServices implements MetadataProvider, ObjectDescriptorProvider, RelationSchemaProvider {
    private final MetadataReadContext context;
    private final MetadataAccess replay;
    private final Map<MetadataCacheKey, RecordedLookup<?>> records = new LinkedHashMap<MetadataCacheKey, RecordedLookup<?>>();

    public SnapshotMetadataServices(MetadataSnapshot snapshot) {
        context = snapshot.getReadContext();
        replay = new DefaultMetadataReplayProviderFactory().create(snapshot);
        for (RecordedLookup<?> read : snapshot.getLookups()) {
            records.put(read.getKey(), read);
        }
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        boolean bundled = records.values().stream().anyMatch(read -> read.getLookupShape()
                .orElse(ProviderCapabilities.LookupShape.UNKNOWN)
                == ProviderCapabilities.LookupShape.BUNDLED_LOOKUP);
        boolean unknown = records.values().stream().anyMatch(read -> read.getLookupShape().orElse(ProviderCapabilities.LookupShape.UNKNOWN)
            == ProviderCapabilities.LookupShape.UNKNOWN);
        return new ProviderCapabilities(EnumSet.of(MetadataFacet.OBJECT_DESCRIPTOR, MetadataFacet.RELATION_SCHEMA,
            MetadataFacet.RELATION_METADATA), bundled ? ProviderCapabilities.LookupShape.BUNDLED_LOOKUP
                    : unknown ? ProviderCapabilities.LookupShape.UNKNOWN : ProviderCapabilities.LookupShape.FACET_LOOKUP, false);
    }

    @Override
    public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext scope) {
        LookupResult<ObjectDescriptor> result = replay.lookupObject(name, scope);
        if (!recorded(name, scope, MetadataFacet.OBJECT_DESCRIPTOR)) {
            LookupResult<RelationMetadata> relation = replay.lookupRelation(name, scope);
            if (recorded(name, scope, MetadataFacet.RELATION_METADATA)) {
                return convert(relation, relation.getValue().map(RelationMetadata::getObject));
            }
            return missing(name, MetadataFacet.OBJECT_DESCRIPTOR);
        }
        return result;
    }

    @Override
    public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext scope) {
        LookupResult<RelationSchema> result = replay.lookupSchema(name, scope);
        if (!recorded(name, scope, MetadataFacet.RELATION_SCHEMA)) {
            LookupResult<RelationMetadata> relation = replay.lookupRelation(name, scope);
            if (recorded(name, scope, MetadataFacet.RELATION_METADATA)) {
                return convert(relation, relation.getValue().map(RelationMetadata::getSchema));
            }
            return missing(name, MetadataFacet.RELATION_SCHEMA);
        }
        return result;
    }

    @Override
    public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext scope) {
        LookupResult<RelationMetadata> relation = replay.lookupRelation(name, scope);
        if (recorded(name, scope, MetadataFacet.RELATION_METADATA)) {
            return relation;
        }
        LookupResult<ObjectDescriptor> object = lookupObject(name, scope);
        LookupResult<RelationSchema> schema = lookupSchema(name, scope);
        if (object.getValue().isPresent() && schema.getValue().isPresent()) {
            return LookupResult.found(new RelationMetadata(object.getValue().get(), schema.getValue().get()), diagnostics(object, schema));
        }
        LookupStatus status = combinedStatus(object.getStatus(), schema.getStatus());
        return new LookupResult<RelationMetadata>(status, Optional.<RelationMetadata>empty(), diagnostics(object, schema));
    }

    public HiveMetadataProvider asLegacyProvider() {
        return identifier -> {
            LookupResult<RelationMetadata> result = lookupRelation(
                    new QualifiedName(Arrays.asList(identifier.getDatabaseName(), identifier.getTableName())), context);
            if (result.getStatus() == LookupStatus.ACCESS_ERROR) {
                String message = result.getDiagnostics().getDiagnostics().stream().map(Diagnostic::getMessage)
                        .collect(java.util.stream.Collectors.joining("; "));
                throw new IllegalStateException(message.isEmpty() ? "Recorded metadata access error" : message);
            }
            return result.getValue().map(value -> legacy(identifier, value)).orElse(null);
        };
    }

    @Override
    public ProviderCapabilities.LookupShape getLookupShape(QualifiedName name, MetadataFacet facet) {
        RecordedLookup<?> read = records.get(new MetadataCacheKey(context, name, facet));
        if (read == null) {
            read = records.get(new MetadataCacheKey(context, name, MetadataFacet.RELATION_METADATA));
        }
        return read == null ? ProviderCapabilities.LookupShape.UNKNOWN : read.getLookupShape().orElse(ProviderCapabilities.LookupShape.UNKNOWN);
    }

    private boolean recorded(QualifiedName name, MetadataReadContext scope, MetadataFacet facet) {
        return records.containsKey(new MetadataCacheKey(scope, name, facet));
    }

    private static <A, B> LookupResult<B> convert(LookupResult<A> result, Optional<B> value) {
        return new LookupResult<B>(result.getStatus(), value, result.getDiagnostics());
    }

    private static LookupStatus combinedStatus(LookupStatus left, LookupStatus right) {
        for (LookupStatus status : new LookupStatus[] { LookupStatus.ACCESS_ERROR, LookupStatus.NOT_FOUND,
                LookupStatus.CAPABILITY_MISSING, LookupStatus.UNKNOWN }) {
            if (left == status || right == status) {
                return status;
            }
        }
        throw new IllegalArgumentException("found facets must be composed before selecting an absent status");
    }

    private static DiagnosticBag diagnostics(LookupResult<?> left, LookupResult<?> right) {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(left.getDiagnostics().getDiagnostics());
        diagnostics.addAll(right.getDiagnostics().getDiagnostics());
        return new DiagnosticBag(diagnostics);
    }

    private static <T> LookupResult<T> missing(QualifiedName name, MetadataFacet facet) {
        Diagnostic diagnostic = new Diagnostic(AnalysisStage.METADATA, "METADATA_REPLAY_MISS",
                "Snapshot has no captured " + facet + " for " + name.getDisplayName(), SourceRef.synthetic("metadata-replay"));
        return LookupResult.unknown(new DiagnosticBag(Collections.singletonList(diagnostic)));
    }

    private static HiveTableMetadata legacy(TableIdentifier identifier, RelationMetadata value) {
        List<String> columns = new ArrayList<>();
        value.getSchema().getColumns().forEach(column -> columns.add(column.getName()));
        if (value.getObject().getKind() == ObjectDescriptor.Kind.VIEW) {
            Optional<String> definition = value.getObject().getDefinition().flatMap(DefinitionRef::getDefinitionText);
            return HiveTableMetadata.view(identifier, columns, definition.orElse(null));
        }
        return HiveTableMetadata.table(identifier, columns);
    }
}
