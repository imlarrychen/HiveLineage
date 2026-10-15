package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.dialect.DialectProfile;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.frontend.DefaultVariableResolver;
import com.panfan.hivelineage.v2.frontend.ExpansionResult;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.frontend.ScriptOptions;
import com.panfan.hivelineage.v2.frontend.ScriptPreprocessResult;
import com.panfan.hivelineage.v2.frontend.VariableContext;
import com.panfan.hivelineage.v2.frontend.VariableOptions;
import com.panfan.hivelineage.v2.frontend.VariableResolutionPolicy;
import com.panfan.hivelineage.v2.ir.identity.NodeId;
import com.panfan.hivelineage.v2.ir.relation.BoundRelation;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.ScriptSourceProvider;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.semantic.ViewTemplateCache;
import com.panfan.hivelineage.v2.semantic.ViewTemplateKey;
import com.panfan.hivelineage.v2.session.DefinitionCoordinator;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.SyntaxTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** External definition SQL always crosses the same explicit frontend stages. */
public final class DefaultDefinitionCoordinator implements DefinitionCoordinator<PreparedDefinition> {
    private final SyntaxFrontend frontend;
    private final ScriptSourceProvider sourceProvider;
    private final ViewTemplateCache cache;
    private final Map<String, SourceDocument> sources;
    private final ResourceLimits limits;
    private final com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor;
    private final Map<String, ExpandedUnit> frontendEvidence = new LinkedHashMap<String, ExpandedUnit>();

    public DefaultDefinitionCoordinator(SyntaxFrontend frontend, ScriptSourceProvider sourceProvider,
                                        ViewTemplateCache cache, Map<String, SourceDocument> sources, ResourceLimits limits) {
        this(frontend, sourceProvider, cache, sources, limits, new DefaultScriptPreprocessor());
    }

    public DefaultDefinitionCoordinator(SyntaxFrontend frontend, ScriptSourceProvider sourceProvider,
            ViewTemplateCache cache, Map<String, SourceDocument> sources, ResourceLimits limits,
            com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor) {
        this.preprocessor = java.util.Objects.requireNonNull(preprocessor, "preprocessor");
        this.frontend = frontend;
        this.sourceProvider = sourceProvider;
        this.cache = cache;
        this.sources = sources;
        this.limits = limits;
    }

    @Override
    public LookupResult<PreparedDefinition> prepare(DefinitionRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        try {
            return prepareDefinition(request);
        } catch (com.panfan.hivelineage.v2.common.ResourceLimitException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            return failed("VIEW_PREPARATION_FAILED", failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage(), request.getReference().getSource());
        }
    }

    private LookupResult<PreparedDefinition> prepareDefinition(DefinitionRequest request) {
        DefinitionRef definition = request.getReference();
        if (request.getRemainingDepth() == 0 || request.getExpansionPath().contains(definition.getDefinitionId())
            || request.getExpansionPath().size() >= limits.getMaxExpansionDepth()) {
            return failed("VIEW_EXPANSION_LIMIT", "Definition depth limit or cycle: " + definition.getDefinitionId(), definition.getSource());
        }
        LookupResult<SourceDocument> read = sourceProvider.read(definition);
        if (!read.getValue().isPresent()) {
            return new LookupResult<>(read.getStatus(), Optional.<PreparedDefinition>empty(), read.getDiagnostics());
        }
        SourceDocument document = read.getValue().get();
        if (document.getText().length() > limits.getMaxScriptCharacters()) {
            throw new com.panfan.hivelineage.v2.common.ResourceLimitException("Definition exceeds configured source size");
        }
        sources.put(document.getSourceId(), document);
        DialectProfile dialect = new DialectProfile(definition.getDefinitionContext().getDialectId(),
            definition.getDefinitionContext().getParserVersion(), definition.getDefinitionContext().getRuleVersion(), Collections.<String>emptySet());
        String digest = UUID.nameUUIDFromBytes(document.getText().getBytes(StandardCharsets.UTF_8)).toString();
        ScriptPreprocessResult script = preprocessor.preprocess(document.getSourceId(), document.getText(), ScriptOptions.defaults());
        if (script.getUnits().size() != 1 || script.getOutcome() == StageOutcome.FAILED) {
            List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(read.getDiagnostics().getDiagnostics());
            diagnostics.addAll(script.getDiagnostics());
            return failed("VIEW_PARSE_FAILED", "A relation definition must contain exactly one SQL statement", definition.getSource(), diagnostics);
        }
        Map<String, Map<String, String>> variables = variables(definition.getDefinitionContext().getVariables());
        ExpansionResult expanded = new DefaultVariableResolver().expand(script.getUnits().get(0), new VariableContext(variables),
            new VariableOptions(VariableResolutionPolicy.PRESERVE_AND_WARN, null, limits.getMaxScriptCharacters()));
        if (expanded.getUnit() == null) {
            if (expanded.getDiagnostics().stream().anyMatch(diagnostic -> "EXPANSION_SIZE_LIMIT".equals(diagnostic.getCode()))) {
                throw new com.panfan.hivelineage.v2.common.ResourceLimitException("Expanded definition exceeds configured source size");
            }
            List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(read.getDiagnostics().getDiagnostics());
            diagnostics.addAll(script.getDiagnostics());
            diagnostics.addAll(expanded.getDiagnostics());
            return failed("VIEW_PARSE_FAILED", "Definition variables could not be prepared",
                    definition.getSource(), diagnostics);
        }
        sources.put(expanded.getUnit().getExpandedSourceId(), new SourceDocument(expanded.getUnit().getExpandedSourceId(),
                expanded.getUnit().getExpandedText()));
        frontendEvidence.put(expanded.getUnit().getExpandedSourceId(), expanded.getUnit());
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(read.getDiagnostics().getDiagnostics());
        diagnostics.addAll(script.getDiagnostics());
        diagnostics.addAll(expanded.getDiagnostics());
        ViewTemplateKey key = new ViewTemplateKey(definition.getDefinitionId(), digest,
            dialect.getDialectId() + ":" + dialect.getParserVersion() + ":" + dialect.getSemanticRuleVersion(),
            DefinitionContextFingerprint.of(definition.getDefinitionContext(), document.getSourceId(),
                    expanded.getUnit().getExpandedText()),
            definition.getDefinitionContext().getReadContext());
        Optional<SyntaxTemplate> cached = cache.get(key);
        if (cached.isPresent()) {
            diagnostics.addAll(cached.get().getDiagnostics());
            return LookupResult.found(new PreparedDefinition(definition.getDefinitionId(), cached,
                    Optional.<BoundRelation>empty(), definition.getSource()),
                    new DiagnosticBag(distinct(diagnostics)));
        }
        SyntaxResult parsed = frontend.parse(expanded.getUnit(), dialect);
        diagnostics.addAll(parsed.getDiagnostics());
        if (parsed.getStatement() == null) {
            return failed("VIEW_PARSE_FAILED", "Definition failed independent syntax parsing", definition.getSource(), diagnostics);
        }
        List<Diagnostic> templateDiagnostics = new ArrayList<Diagnostic>(script.getDiagnostics());
        templateDiagnostics.addAll(expanded.getDiagnostics());
        templateDiagnostics.addAll(parsed.getDiagnostics());
        SyntaxTemplate template = new SyntaxTemplate(new NodeId(definition.getDefinitionId().getValue() + ":syntax"), parsed.getStatement(),
                definition.getSource(), distinct(templateDiagnostics), Optional.of(expanded.getUnit()));
        cache.put(key, template);
        return LookupResult.found(new PreparedDefinition(definition.getDefinitionId(), Optional.of(template),
                Optional.<BoundRelation>empty(), definition.getSource()),
            new DiagnosticBag(distinct(diagnostics)));
    }

    public Map<String, ExpandedUnit> getFrontendEvidence() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, ExpandedUnit>(frontendEvidence));
    }

    private static Map<String, Map<String, String>> variables(Map<String, String> source) {
        Map<String, Map<String, String>> namespaces = new LinkedHashMap<String, Map<String, String>>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            int colon = entry.getKey().indexOf(':');
            String namespace = colon < 0 ? "hivevar" : entry.getKey().substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            String name = colon < 0 ? entry.getKey() : entry.getKey().substring(colon + 1);
            namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<String, String>()).put(name, entry.getValue());
        }
        return namespaces;
    }

    private static List<Diagnostic> distinct(List<Diagnostic> diagnostics) {
        return DiagnosticBag.distinct(diagnostics);
    }

    private static LookupResult<PreparedDefinition> failed(String code, String message, SourceRef source) {
        return failed(code, message, source, Collections.<Diagnostic>emptyList());
    }

    private static LookupResult<PreparedDefinition> failed(String code, String message, SourceRef source, List<Diagnostic> preceding) {
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(preceding);
        diagnostics.add(new Diagnostic(AnalysisStage.NORMALIZATION, code, message, source));
        return LookupResult.accessError(new DiagnosticBag(distinct(diagnostics)));
    }
}
