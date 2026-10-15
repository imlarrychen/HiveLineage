package com.panfan.hivelineage.v2.metadata;

import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.ir.TypeRef;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.ir.quality.SchemaState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Adapts data only; neither parser nor V1 lineage engine is invoked.
 */
public final class V1MetadataProviderAdapter implements MetadataProvider {
    private static final Map<HiveMetadataCache, CacheOwner> CACHE_OWNERS = new WeakHashMap<>();
    private final LegacyMetadataBinding binding;
    private final ProviderCapabilities capabilities;

    public V1MetadataProviderAdapter(LegacyMetadataBinding binding) {
        this.binding = java.util.Objects.requireNonNull(binding, "binding");
        capabilities = new ProviderCapabilities(EnumSet.of(MetadataFacet.OBJECT_DESCRIPTOR,
                MetadataFacet.RELATION_SCHEMA, MetadataFacet.RELATION_METADATA, MetadataFacet.DEFINITION),
                ProviderCapabilities.LookupShape.BUNDLED_LOOKUP, binding.isAuthoritativeNullMeansNotFound());
        if (binding.getCache().isPresent() && binding.getProvider().isPresent()) {
            synchronized (CACHE_OWNERS) {
                CacheOwner owner = CACHE_OWNERS.get(binding.getCache().get());
                if (owner != null && (owner.provider.get() != binding.getProvider().get()
                        || !owner.context.equals(binding.getReadContext()))) {
                    throw new IllegalArgumentException("V1 cache is already bound to another metadata source/context");
                }
                CACHE_OWNERS.put(binding.getCache().get(), new CacheOwner(binding.getProvider().get(), binding.getReadContext()));
            }
        }
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
        if (!context.equals(binding.getReadContext())) {
            throw new IllegalArgumentException("adapter context mismatch");
        }
        if (!binding.getProvider().isPresent()) {
            return LookupResult.capabilityMissing(DiagnosticBag.empty());
        }
        List<String> parts = name.getParts();
        String database = parts.size() > 1 ? parts.get(parts.size() - 2) : "default";
        TableIdentifier identifier = TableIdentifier.of(context.getClusterName(), database, parts.get(parts.size() - 1));
        try {
            HiveTableMetadata value = binding.getCache().isPresent()
                    ? binding.getCache().get().getTableMetadata(identifier, binding.getProvider().get())
                    : binding.getProvider().get().getTableMetadata(identifier);
            if (value == null) {
                return binding.isAuthoritativeNullMeansNotFound()
                        ? LookupResult.notFound(DiagnosticBag.empty()) : LookupResult.unknown(DiagnosticBag.empty());
            }
            return LookupResult.found(convert(value, context));
        } catch (RuntimeException failure) {
            Diagnostic diagnostic = new Diagnostic(AnalysisStage.METADATA, "METADATA_ACCESS_ERROR",
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), SourceRef.synthetic("metadata"));
            return LookupResult.accessError(new DiagnosticBag(Collections.singletonList(diagnostic)));
        }
    }

    private RelationMetadata convert(HiveTableMetadata value, MetadataReadContext context) {
        TableIdentifier table = value.getTableIdentifier();
        QualifiedName name = new QualifiedName(Arrays.asList(table.getDatabaseName(), table.getTableName()));
        StringBuilder content = new StringBuilder();
        appendContent(content, String.valueOf(value.getObjectType()));
        content.append(value.getColumns().size()).append(':');
        for (String column : value.getColumns()) {
            appendContent(content, column);
        }
        appendContent(content, value.getViewOriginalSql());
        String fingerprint = UUID.nameUUIDFromBytes(content.toString().getBytes(StandardCharsets.UTF_8)).toString();
        MetadataProvenance provenance = new MetadataProvenance(context.getSourceId(),
                value.isView() && binding.getProvider().get() instanceof com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider
                        ? MetadataProvenance.Origin.SYNTHESIZED_DEFINITION : MetadataProvenance.Origin.PROVIDER_DEFINED,
                Optional.of(fingerprint), Optional.of(System.currentTimeMillis()));
        String stable = context.getSourceId() + ":" + context.getClusterName() + ":" + name.getDisplayName();
        Optional<DefinitionRef> definition = Optional.empty();
        if (value.isView() && value.getViewOriginalSql() != null && !value.getViewOriginalSql().trim().isEmpty()) {
            String definitionSource = "definition:" + stable + ":" + fingerprint;
            definition = Optional.of(new DefinitionRef(new DefinitionId(definitionSource), Optional.of(value.getViewOriginalSql()),
                    new DefinitionContext(Optional.of(new QualifiedName(Collections.singletonList(table.getDatabaseName()))),
                            Collections.<String, String>emptyMap(), "hive", "4.0.1", "v2", context), provenance,
                    MetadataVersion.unknown(), SourceRef.synthetic(definitionSource)));
        }
        ObjectDescriptor object = new ObjectDescriptor(new ObjectId(stable), name,
                value.isView() ? ObjectDescriptor.Kind.VIEW : ObjectDescriptor.Kind.TABLE, definition, provenance, MetadataVersion.unknown());
        List<ColumnMetadata> columns = new ArrayList<>();
        for (String column : value.getColumns()) {
            columns.add(new ColumnMetadata(column, columns.size(), TypeRef.unknown(), ColumnMetadata.Role.UNKNOWN,
                    ColumnMetadata.Nullability.UNKNOWN));
        }
        return new RelationMetadata(object, new RelationSchema(SchemaState.KNOWN, columns, provenance, MetadataVersion.unknown()));
    }

    private static void appendContent(StringBuilder content, String value) {
        if (value == null) {
            content.append("-1:");
        } else {
            content.append(value.length()).append(':').append(value);
        }
    }

    private static final class CacheOwner {
        private final java.lang.ref.WeakReference<HiveMetadataProvider> provider;
        private final MetadataReadContext context;

        private CacheOwner(HiveMetadataProvider provider, MetadataReadContext context) {
            this.provider = new java.lang.ref.WeakReference<>(provider);
            this.context = context;
        }
    }
}
