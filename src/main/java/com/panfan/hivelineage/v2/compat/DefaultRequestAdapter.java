package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.api.AnalysisCapabilities;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.DefinitionPolicy;
import com.panfan.hivelineage.v2.api.ResourceLimits;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.StarMappingPolicy;
import com.panfan.hivelineage.v2.api.StatementPublicationPolicy;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.api.VariablePolicy;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.session.InitialSessionContext;
import com.panfan.hivelineage.v2.session.VariableKey;
import com.panfan.hivelineage.v2.session.VariableState;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class DefaultRequestAdapter implements RequestAdapter {
    private static final java.util.List<ProviderIdentity> PROVIDER_IDS = new java.util.ArrayList<>();
    private static long nextProviderId;
    private final StarMappingPolicy starMappingPolicy;

    public DefaultRequestAdapter() {
        this(StarMappingPolicy.CURRENT_API_TARGET_MAPPING);
    }

    public DefaultRequestAdapter(StarMappingPolicy starMappingPolicy) {
        this.starMappingPolicy = java.util.Objects.requireNonNull(starMappingPolicy, "starMappingPolicy");
    }

    @Override
    public ScriptAnalysisRequest adapt(LineageAnalyzeRequest request) {
        TaskIdentity task = new TaskIdentity(text(request.getWorkflowId(), "default"), text(request.getTaskId(), "task"),
                text(request.getTaskVersion(), "1"));
        String sessionId = task.getWorkflowId() + ":" + task.getTaskId() + ":" + task.getTaskVersion();
        Map<VariableKey, VariableState> variables = new LinkedHashMap<>();
        request.getRuntimeVariables().forEach((key, value) -> variables.put(runtimeKey(key),
                new VariableState(VariableState.Resolution.KNOWN, Optional.of(value), SourceRef.synthetic("runtime"))));
        InitialSessionContext session = new InitialSessionContext(sessionId, task.getWorkflowId(), task.getTaskId(), task.getTaskVersion(),
                request.getClusterName(), request.getDefaultDatabase() == null || request.getDefaultDatabase().trim().isEmpty() ? Optional.<QualifiedName>empty()
                : Optional.of(new QualifiedName(Collections.singletonList(request.getDefaultDatabase().trim()))),
                variables, Collections.<String, String>emptyMap());
        MetadataReadContext reads = new MetadataReadContext(providerId(request.getMetadataProvider()), request.getClusterName(),
                Optional.<String>empty(), Collections.<String, String>emptyMap());
        LineageAnalyzeOptions options = request.getOptions();
        EnumSet<AnalysisCapability> capabilities = EnumSet.noneOf(AnalysisCapability.class);
        if (options.getParseMode() != LineageParseMode.COLUMN_ONLY) {
            capabilities.add(AnalysisCapability.TABLE_LINEAGE);
        }
        if (options.getParseMode() != LineageParseMode.TABLE_ONLY) {
            capabilities.add(AnalysisCapability.COLUMN_LINEAGE);
        }
        if (options.isParseJoinConditions()) {
            capabilities.add(AnalysisCapability.JOIN_CONDITION);
        }
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(capabilities), VariablePolicy.CURRENT_API_FALLBACK,
                DefinitionPolicy.CURRENT_API_CTE_REUSE, starMappingPolicy, StatementPublicationPolicy.CURRENT_API,
                options.isIncludeTemporaryLineage(), options.isIncludeIgnoredStatements(), options.isExpandViewLineage(),
                Math.max(0, options.getMaxViewExpandDepth()), options.isFailFast(), options.getFallbackVariableValue());
        DialectProfile dialect = new DialectProfile("hive", "4.0.1", "v2", Collections.<String>emptySet());
        return new ScriptAnalysisRequest(task, new SourceDocument("script:" + sessionId, text(request.getRawScript(), "")), dialect,
                session, reads, contract, new ResourceLimits(16_000_000, 64, 1_000_000, 2_000_000, 100_000));
    }

    private static VariableKey runtimeKey(String text) {
        int colon = text.indexOf(':');
        if (colon > 0) {
            String namespace = text.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            VariableKey.Namespace kind = "hivevar".equals(namespace) ? VariableKey.Namespace.HIVEVAR
                    : "hiveconf".equals(namespace) ? VariableKey.Namespace.HIVECONF
                      : "system".equals(namespace) ? VariableKey.Namespace.SYSTEM
                        : "env".equals(namespace) ? VariableKey.Namespace.ENVIRONMENT : VariableKey.Namespace.RUNTIME;
            if (kind != VariableKey.Namespace.RUNTIME) {
                return new VariableKey(kind, text.substring(colon + 1));
            }
        }
        return new VariableKey(VariableKey.Namespace.RUNTIME, text);
    }

    private static synchronized String providerId(Object provider) {
        if (provider == null) {
            return "metadata:none";
        }
        PROVIDER_IDS.removeIf(identity -> identity.provider.get() == null);
        for (ProviderIdentity identity : PROVIDER_IDS) {
            if (identity.provider.get() == provider) {
                return identity.id;
            }
        }
        String id = "metadata:provider:" + (++nextProviderId);
        PROVIDER_IDS.add(new ProviderIdentity(provider, id));
        return id;
    }

    private static final class ProviderIdentity {
        private final java.lang.ref.WeakReference<Object> provider;
        private final String id;

        private ProviderIdentity(Object provider, String id) {
            this.provider = new java.lang.ref.WeakReference<>(provider);
            this.id = id;
        }
    }

    private static String text(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
