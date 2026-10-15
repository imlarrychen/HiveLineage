package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.DiagnosticBag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * No missing replay read can escape to a live provider.
 */
public final class DefaultMetadataReplayProviderFactory implements MetadataReplayProviderFactory {
    @Override
    public MetadataAccess create(MetadataSnapshot snapshot) {
        return new Replay(snapshot);
    }

    private static final class Replay implements MetadataAccess {
        private final MetadataReadContext context;
        private final Map<MetadataCacheKey, RecordedLookup<?>> reads = new LinkedHashMap<>();

        private Replay(MetadataSnapshot snapshot) {
            context = snapshot.getReadContext();
            for (RecordedLookup<?> lookup : snapshot.getLookups()) {
                if (reads.put(lookup.getKey(), lookup) != null) {
                    throw new IllegalArgumentException("duplicate snapshot facet");
                }
            }
        }

        @Override
        public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext scope) {
            return lookup(name, scope, MetadataFacet.OBJECT_DESCRIPTOR, ObjectDescriptor.class);
        }

        @Override
        public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext scope) {
            return lookup(name, scope, MetadataFacet.RELATION_SCHEMA, RelationSchema.class);
        }

        @Override
        public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext scope) {
            return lookup(name, scope, MetadataFacet.RELATION_METADATA, RelationMetadata.class);
        }

        @SuppressWarnings("unchecked")
        private <T> LookupResult<T> lookup(QualifiedName name, MetadataReadContext scope,
                                           MetadataFacet facet, Class<T> type) {
            if (!context.equals(scope)) {
                throw new IllegalArgumentException("replay context mismatch");
            }
            RecordedLookup<?> read = reads.get(new MetadataCacheKey(scope, name, facet));
            if (read == null) {
                return LookupResult.unknown(DiagnosticBag.empty());
            }
            if (!read.getPayloadType().equals(type)) {
                throw new IllegalArgumentException("replay payload type mismatch");
            }
            return (LookupResult<T>) read.getResult();
        }
    }
}
