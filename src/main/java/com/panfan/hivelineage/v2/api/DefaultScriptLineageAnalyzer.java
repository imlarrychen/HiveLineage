package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.analysis.DefaultJoinConditionAnalysis;
import com.panfan.hivelineage.v2.analysis.DefaultLineageEngine;
import com.panfan.hivelineage.v2.analysis.DefaultLineageRuleRegistry;
import com.panfan.hivelineage.v2.analysis.JoinFacts;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
import com.panfan.hivelineage.v2.frontend.DefaultVariableResolver;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.ExpansionResult;
import com.panfan.hivelineage.v2.frontend.ScriptOptions;
import com.panfan.hivelineage.v2.frontend.ScriptPreprocessResult;
import com.panfan.hivelineage.v2.frontend.ScriptUnit;
import com.panfan.hivelineage.v2.frontend.VariableContext;
import com.panfan.hivelineage.v2.frontend.VariableOptions;
import com.panfan.hivelineage.v2.frontend.VariableResolutionPolicy;
import com.panfan.hivelineage.v2.ir.identity.DefinitionId;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import com.panfan.hivelineage.v2.metadata.CapturedMetadataAccess;
import com.panfan.hivelineage.v2.metadata.BudgetedMetadataAccess;
import com.panfan.hivelineage.v2.metadata.BudgetedScriptSourceProvider;
import com.panfan.hivelineage.v2.metadata.ExternalReadBudget;
import com.panfan.hivelineage.v2.metadata.MetadataAccess;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefinitionTextSourceProvider;
import com.panfan.hivelineage.v2.metadata.ScriptSourceProvider;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.BindingContext;
import com.panfan.hivelineage.v2.semantic.BindingDemand;
import com.panfan.hivelineage.v2.semantic.BindingOutcome;
import com.panfan.hivelineage.v2.semantic.BindingResult;
import com.panfan.hivelineage.v2.semantic.DefaultAnalysisDemandPlanner;
import com.panfan.hivelineage.v2.semantic.DefaultFunctionCatalog;
import com.panfan.hivelineage.v2.semantic.DefaultIrVerifier;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;
import com.panfan.hivelineage.v2.semantic.DefaultViewTemplateCache;
import com.panfan.hivelineage.v2.semantic.ViewTemplateCache;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.semantic.SemanticBinder;
import com.panfan.hivelineage.v2.semantic.VerificationResult;
import com.panfan.hivelineage.v2.session.DefaultSessionEffectCommitter;
import com.panfan.hivelineage.v2.session.DefaultSessionEffectPlanner;
import com.panfan.hivelineage.v2.session.DefinitionRequest;
import com.panfan.hivelineage.v2.session.SessionSnapshot;
import com.panfan.hivelineage.v2.session.VariableKey;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stage composition and request lifecycle; semantic rules remain in their own components. */
public final class DefaultScriptLineageAnalyzer implements ScriptLineageAnalyzer, WorkflowBatchAnalyzer {
    private final SyntaxFrontend frontend;
    private final SemanticBinder binder;
    private final DefaultMetadataAccessFactory metadata;
    private final com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor;
    private final ViewTemplateCache templates;
    private final ScriptSourceProvider sourceProvider;

    public DefaultScriptLineageAnalyzer() {
        this(new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()), new DefaultSemanticBinder(), new DefaultMetadataAccessFactory());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata) {
        this(frontend, binder, metadata, new DefaultScriptPreprocessor());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata,
            com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor) {
        this(frontend, binder, metadata, preprocessor, new DefaultViewTemplateCache());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata,
            com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates) {
        this(frontend, binder, metadata, preprocessor, templates, new DefinitionTextSourceProvider());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata,
            com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates,
            ScriptSourceProvider sourceProvider) {
        this.sourceProvider = java.util.Objects.requireNonNull(sourceProvider, "sourceProvider");
        this.templates = java.util.Objects.requireNonNull(templates, "templates");
        this.preprocessor = java.util.Objects.requireNonNull(preprocessor, "preprocessor");
        this.frontend = java.util.Objects.requireNonNull(frontend, "frontend");
        this.binder = java.util.Objects.requireNonNull(binder, "binder");
        this.metadata = java.util.Objects.requireNonNull(metadata, "metadata");
    }

    @Override
    public ScriptAnalysisResult analyzeScript(ScriptAnalysisRequest request, WorkflowContext workflow) {
        if (!request.getTask().getWorkflowId().equals(workflow.getWorkflowId())
            || !request.getMetadataContext().equals(workflow.getReadContext())) {
            throw new IllegalArgumentException("request and workflow identities/read contexts differ");
        }
        if (request.getSource().getText().length() > request.getResourceLimits().getMaxScriptCharacters()) {
            throw new IllegalArgumentException("RESOURCE_LIMIT: script exceeds configured source size");
        }
        CapturedMetadataAccess captured = metadata.create(workflow);
        ExternalReadBudget readBudget = new ExternalReadBudget(request.getResourceLimits().getMaxExternalReads());
        MetadataAccess access = new BudgetedMetadataAccess(captured, readBudget);
        Map<String, SourceDocument> sources = new LinkedHashMap<>();
        sources.put(request.getSource().getSourceId(), request.getSource());
        DefaultDefinitionCoordinator definitions = new DefaultDefinitionCoordinator(frontend,
            new BudgetedScriptSourceProvider(sourceProvider, readBudget), templates, sources, request.getResourceLimits(), preprocessor);
        Map<DefinitionId, PreparedDefinition> prepared = new LinkedHashMap<>();
        Map<DefinitionId, DiagnosticBag> failedDefinitions = new LinkedHashMap<>();
        SessionSnapshot session = SessionSnapshot.initial(request.getInitialSession());
        ScriptPreprocessResult script = preprocessor.preprocess(request.getSource().getSourceId(), request.getSource().getText(), new ScriptOptions(";", true, 100_000));
        List<StatementAnalysisResult> statements = new ArrayList<>();
        List<DatasetVersionRecord> versions = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>(script.getDiagnostics());
        int ignored = 0;
        int failed = 0;
        for (ScriptUnit raw : script.getUnits()) {
            StatementRun run = analyzeStatement(request, raw, session, access, definitions, prepared, failedDefinitions, sources);
            statements.add(run.result);
            diagnostics.addAll(run.result.getDiagnostics().getDiagnostics());
            SessionSnapshot previous = session;
            run.result.getBoundUnit().ifPresent(unit -> run.session.getDatasetVersions().forEach((objectId, state) -> {
                if (previous.getDatasetVersions().get(objectId) != state) {
                    versions.add(new DatasetVersionRecord(run.result.getIdentity(), objectId,
                        Optional.ofNullable(unit.getObjects().get(objectId)).map(object -> object.getName()), state, unit.getExecutionAssumption()));
                }
            }));
            session = run.session;
            ignored += run.result.getStatus() == StatementStatus.IGNORED ? 1 : 0;
            failed += run.result.getStatus() == StatementStatus.FAILED ? 1 : 0;
            if (failed > 0 && request.getContract().isFailFast()) {
                break;
            }
        }
        AnalysisBasis basis = new AnalysisBasis(request.getDialect(), request.getInitialSession(), captured.manifest(), request.getContract());
        Map<String, ExpandedUnit> frontendEvidence = new LinkedHashMap<>(definitions.getFrontendEvidence());
        statements.forEach(statement -> statement.getFrontendEvidence().ifPresent(evidence ->
            frontendEvidence.put(evidence.getExpandedSourceId(), evidence)));
        return new ScriptAnalysisResult(request.getTask(), new EngineIdentity("hive-lineage-v2", "2.0.0", request.getDialect().getSemanticRuleVersion()),
            statements, new StatementCounts(script.getUnits().size(), ignored, failed), new DiagnosticBag(diagnostics), Optional.of(basis), sources, frontendEvidence, versions);
    }

    private StatementRun analyzeStatement(ScriptAnalysisRequest request, ScriptUnit raw, SessionSnapshot session,
            MetadataAccess access, DefaultDefinitionCoordinator coordinator, Map<DefinitionId, PreparedDefinition> prepared,
            Map<DefinitionId, DiagnosticBag> failedDefinitions, Map<String, SourceDocument> sources) {
        int index = raw.getStatementIndex();
        TaskIdentity task = request.getTask();
        StatementIdentity identity = new StatementIdentity(task, index,
            task.getWorkflowId() + "_" + task.getTaskId() + "_" + task.getTaskVersion() + "_" + index);
        SourceDocument rawSql = new SourceDocument(raw.getSourceId(), raw.getRawText().trim());
        SourceDocument normalized = rawSql;
        List<Diagnostic> diagnostics = new ArrayList<>();
        String type = "UNSUPPORTED";
        AnalysisStage stage = AnalysisStage.FRONTEND;
        Optional<ExpandedUnit> frontendEvidence = Optional.empty();
        try {
            AnalysisContract contract = request.getContract();
            ExpansionResult expansion = new DefaultVariableResolver().expand(raw, variables(session, contract), new VariableOptions(
                contract.getVariablePolicy() == VariablePolicy.CURRENT_API_FALLBACK ? VariableResolutionPolicy.LEGACY_FALLBACK : contract.getVariablePolicy() == VariablePolicy.SPEC_VARIABLES
                    ? VariableResolutionPolicy.SPEC_VARIABLES : VariableResolutionPolicy.PRESERVE_AND_WARN,
                contract.getFallbackVariableValue(), request.getResourceLimits().getMaxScriptCharacters()));
            diagnostics.addAll(expansion.getDiagnostics());
            if (expansion.getUnit() == null) {
                if (expansion.getDiagnostics().stream().anyMatch(diagnostic -> "EXPANSION_SIZE_LIMIT".equals(diagnostic.getCode()))) {
                    throw new ResourceLimitException("Expanded statement exceeds configured source size");
                }
                throw new StageFailure("VARIABLE_EXPANSION_FAILED", "Variables could not be expanded");
            }
            ExpandedUnit expanded = expansion.getUnit();
            frontendEvidence = Optional.of(expanded);
            if (expanded.getExpandedText().length() > request.getResourceLimits().getMaxScriptCharacters()) {
                throw new ResourceLimitException("Expanded statement exceeds configured source size");
            }
            normalized = new SourceDocument(expanded.getExpandedSourceId(), expanded.getExpandedText());
            sources.put(normalized.getSourceId(), normalized);
            stage = AnalysisStage.SYNTAX;
            SyntaxResult parsed = frontend.parse(expanded, request.getDialect());
            diagnostics.addAll(parsed.getDiagnostics());
            if (parsed.getStatement() == null) {
                throw new StageFailure("SQL_PARSE_FAILED", "Independent frontend did not produce a syntax statement");
            }
            SyntaxStatement syntax = parsed.getStatement();
            StatementClassification classification = StatementClassification.of(syntax, contract);
            type = classification.type;
            if (syntax instanceof com.panfan.hivelineage.v2.syntax.OtherStatementSyntax
                && "INVALID_USE_DATABASE".equals(((com.panfan.hivelineage.v2.syntax.OtherStatementSyntax) syntax).getUnsupportedReason())) {
                throw new StageFailure("INVALID_USE_DATABASE", "USE requires one unquoted or backtick-quoted database identifier");
            }
            if (syntax instanceof com.panfan.hivelineage.v2.syntax.OtherStatementSyntax && classification.formalCandidate) {
                throw new StageFailure("UNSUPPORTED_STATEMENT", "Data-changing statement " + type + " has no lineage binding");
            }
            boolean plainIgnoredQuery = syntax instanceof com.panfan.hivelineage.v2.syntax.QueryStatementSyntax
                && ((com.panfan.hivelineage.v2.syntax.QueryStatementSyntax) syntax).getQuery().getCtes().isEmpty()
                && !contract.getCapabilities().supports(AnalysisCapability.JOIN_CONDITION)
                && contract.getPublicationPolicy() == StatementPublicationPolicy.CURRENT_API;
            if (plainIgnoredQuery || syntax instanceof com.panfan.hivelineage.v2.syntax.OtherStatementSyntax && !classification.formalCandidate) {
                return new StatementRun(new StatementAnalysisResult(identity, type, rawSql, normalized, StatementStatus.IGNORED, false,
                    Optional.<LineageFacts>empty(), Optional.<JoinFacts>empty(), Optional.<BoundAnalysisUnit>empty(), Optional.ofNullable(classification.skipReason),
                    Optional.<String>empty(), Optional.<String>empty(), new DiagnosticBag(diagnostics), frontendEvidence), session);
            }
            BindingDemand demand = new DefaultAnalysisDemandPlanner().plan(syntax, contract);
            stage = AnalysisStage.BINDING;
            BindingResult binding = bind(request, syntax, demand, session, access, coordinator, prepared, failedDefinitions);
            diagnostics.addAll(binding.getDiagnostics().getDiagnostics());
            if (!binding.getUnit().isPresent() || binding.getOutcome() == BindingOutcome.FAILED || binding.getOutcome() == BindingOutcome.UNSUPPORTED) {
                List<Diagnostic> bindingDiagnostics = binding.getDiagnostics().getDiagnostics();
                Diagnostic primary = bindingDiagnostics.isEmpty() ? null : bindingDiagnostics.get(bindingDiagnostics.size() - 1);
                throw new StageFailure(binding.getOutcome() == BindingOutcome.UNSUPPORTED ? "UNSUPPORTED_STATEMENT"
                    : primary == null ? "BINDING_FAILED" : primary.getCode(),
                    primary == null ? "Statement cannot be bound for requested capabilities" : primary.getMessage());
            }
            BoundAnalysisUnit unit = binding.getUnit().get();
            IrResourceGuard resources = new IrResourceGuard();
            resources.checkUnit(unit, request.getResourceLimits());
            stage = AnalysisStage.VERIFICATION;
            VerificationResult verification = new DefaultIrVerifier().verify(unit, demand);
            diagnostics.addAll(verification.getDiagnostics().getDiagnostics());
            if (!verification.getVerifiedUnit().isPresent()) {
                throw new StageFailure("IR_VERIFICATION_FAILED", "Bound IR failed structural or binding verification");
            }
            VerifiedAnalysisUnit verified = verification.getVerifiedUnit().get();
            stage = AnalysisStage.ANALYSIS;
            Optional<LineageFacts> lineage = Optional.empty();
            boolean formal = classification.formalCandidate && !unit.getWrites().isEmpty()
                && (contract.getCapabilities().supports(AnalysisCapability.TABLE_LINEAGE)
                    || contract.getCapabilities().supports(AnalysisCapability.COLUMN_LINEAGE)
                    || contract.getCapabilities().supports(AnalysisCapability.EXTERNAL_DATA_LINEAGE));
            boolean detailedQuery = syntax instanceof com.panfan.hivelineage.v2.syntax.QueryStatementSyntax
                && contract.getPublicationPolicy() == StatementPublicationPolicy.DETAILED;
            if ((formal || detailedQuery) && (contract.getCapabilities().supports(AnalysisCapability.TABLE_LINEAGE) || contract.getCapabilities().supports(AnalysisCapability.COLUMN_LINEAGE)
                || contract.getCapabilities().supports(AnalysisCapability.EXTERNAL_DATA_LINEAGE))) {
                lineage = Optional.of(new DefaultLineageEngine(request.getResourceLimits().getMaxEvidenceEdges()).analyze(verified, new DefaultLineageRuleRegistry()));
                diagnostics.addAll(lineage.get().getDiagnostics().getDiagnostics());
                resources.checkFacts(lineage.get(), request.getResourceLimits());
            }
            Optional<JoinFacts> joins = Optional.empty();
            if (contract.getCapabilities().supports(AnalysisCapability.JOIN_CONDITION)) {
                joins = Optional.of(new DefaultJoinConditionAnalysis(request.getResourceLimits().getMaxEvidenceEdges()).analyze(verified, new DefaultLineageRuleRegistry()));
                diagnostics.addAll(joins.get().getDiagnostics().getDiagnostics());
                resources.checkJoinFacts(joins.get(), request.getResourceLimits());
            }
            boolean published = formal || detailedQuery && lineage.isPresent() || joins.isPresent() && !joins.get().getJoins().isEmpty();
            StatementStatus status = !published ? StatementStatus.IGNORED : binding.getOutcome() == BindingOutcome.PARTIAL ? StatementStatus.PARTIAL_SUCCESS : StatementStatus.SUCCESS;
            com.panfan.hivelineage.v2.session.SessionCommitResult committed = new DefaultSessionEffectCommitter().commit(session,
                new DefaultSessionEffectPlanner().plan(session, unit, SyntaxDefinitionTemplates.prepare(syntax, unit)));
            diagnostics.addAll(committed.getDiagnostics().getDiagnostics());
            SessionSnapshot next = committed.getSnapshot();
            if (published && committed.getOutcome() == com.panfan.hivelineage.v2.session.CommitOutcome.UNCERTAIN) {
                status = StatementStatus.PARTIAL_SUCCESS;
            }
            return new StatementRun(new StatementAnalysisResult(identity, type, rawSql, normalized, status, formal, lineage, joins, Optional.of(unit),
                published ? Optional.<String>empty() : Optional.ofNullable(classification.skipReason), Optional.<String>empty(), Optional.<String>empty(), new DiagnosticBag(diagnostics), frontendEvidence), next);
        } catch (RuntimeException failure) {
            String code = failure instanceof ResourceLimitException ? "RESOURCE_LIMIT"
                : failure instanceof StageFailure ? ((StageFailure) failure).code : stage.name() + "_ERROR";
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            diagnostics.add(new Diagnostic(stage, code, message, raw.getSourceRef()));
            return new StatementRun(new StatementAnalysisResult(identity, type, rawSql, normalized, StatementStatus.FAILED, false,
                Optional.<LineageFacts>empty(), Optional.<JoinFacts>empty(), Optional.<BoundAnalysisUnit>empty(), Optional.<String>empty(),
                Optional.of(code), Optional.of(message), new DiagnosticBag(diagnostics), frontendEvidence), session);
        }
    }

    private BindingResult bind(ScriptAnalysisRequest request, SyntaxStatement syntax, BindingDemand demand, SessionSnapshot session,
            MetadataAccess access, DefaultDefinitionCoordinator coordinator, Map<DefinitionId, PreparedDefinition> prepared,
            Map<DefinitionId, DiagnosticBag> failedDefinitions) {
        List<Diagnostic> definitionDiagnostics = new ArrayList<>();
        for (int attempt = 0; attempt <= request.getResourceLimits().getMaxExpansionDepth(); attempt++) {
            BindingContext context = new BindingContext(session, access, request.getMetadataContext(), prepared,
                new DefaultFunctionCatalog(), request.getDialect(), request.getContract(), failedDefinitions);
            BindingResult result = binder.bind(syntax, context, demand);
            if (result.getOutcome() != BindingOutcome.NEEDS_DEFINITION) {
                result.getUnit().ifPresent(unit -> unit.getRelations().forEach(relation -> {
                    if (relation instanceof com.panfan.hivelineage.v2.ir.relation.Relations.DefinitionInstance) {
                        PreparedDefinition used = prepared.get(((com.panfan.hivelineage.v2.ir.relation.Relations.DefinitionInstance) relation).getDefinitionId());
                        if (used != null) {
                            used.getSyntax().ifPresent(template -> definitionDiagnostics.addAll(template.getDiagnostics()));
                        }
                    }
                }));
                definitionDiagnostics.addAll(result.getDiagnostics().getDiagnostics());
                Map<String, Diagnostic> unique = new LinkedHashMap<>();
                definitionDiagnostics.forEach(diagnostic -> unique.put(diagnostic.getStage() + ":" + diagnostic.getCode() + ":"
                    + (diagnostic.getSourceRef() == null ? "<no source>" : diagnostic.getSourceRef().getSourceId() + ":"
                        + diagnostic.getSourceRef().getSpan().getStart() + ":" + diagnostic.getSourceRef().getSpan().getEnd())
                    + ":" + diagnostic.getMessage(), diagnostic));
                return new BindingResult(result.getOutcome(), result.getUnit(), result.getDefinitionRequests(), new DiagnosticBag(new ArrayList<>(unique.values())));
            }
            boolean progressed = false;
            for (DefinitionRequest definition : result.getDefinitionRequests()) {
                DefinitionId id = definition.getReference().getDefinitionId();
                if (prepared.containsKey(id) || failedDefinitions.containsKey(id)) {
                    continue;
                }
                LookupResult<PreparedDefinition> value = coordinator.prepare(definition);
                definitionDiagnostics.addAll(value.getDiagnostics().getDiagnostics());
                if (value.getValue().isPresent()) {
                    prepared.put(id, value.getValue().get());
                } else {
                    failedDefinitions.put(id, value.getDiagnostics());
                }
                progressed = true;
            }
            if (!progressed) {
                throw new StageFailure("DEFINITION_PROTOCOL_STALLED", "Definition preparation made no progress");
            }
        }
        throw new StageFailure("VIEW_EXPANSION_LIMIT", "Definition expansion exceeded configured retry limit");
    }

    private static VariableContext variables(SessionSnapshot session, AnalysisContract contract) {
        Map<String, Map<String, String>> namespaces = new LinkedHashMap<>();
        session.getVariables().forEach((key, value) -> {
            if (value.getValue().isPresent()) {
                String namespace = key.getNamespace() == VariableKey.Namespace.UNQUALIFIED ? ""
                    : key.getNamespace() == VariableKey.Namespace.ENVIRONMENT ? "env" : key.getNamespace().name().toLowerCase(java.util.Locale.ROOT);
                namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
                if (key.getNamespace() == VariableKey.Namespace.UNQUALIFIED) {
                    namespaces.computeIfAbsent("hiveconf", ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
                }
            }
        });
        if (contract.getVariablePolicy() == VariablePolicy.CURRENT_API_FALLBACK) {
            session.getVariables().forEach((key, value) -> {
                if (key.getNamespace() == VariableKey.Namespace.UNQUALIFIED && value.getValue().isPresent()) {
                    for (String namespace : new String[] { "hiveconf", "hivevar", "runtime" }) {
                        namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
                if (key.getNamespace() == VariableKey.Namespace.UNQUALIFIED) {
                    namespaces.computeIfAbsent("hiveconf", ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
                }
                    }
                }
            });
        }
        return new VariableContext(namespaces);
    }

    @Override
    public WorkflowBatchResult analyze(WorkflowBatchRequest request, WorkflowContext workflow) {
        if (!request.getWorkflowId().equals(workflow.getWorkflowId())) {
            throw new IllegalArgumentException("batch workflow mismatch");
        }
        List<ScriptAnalysisResult> results = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (ScriptAnalysisRequest task : request.getTasks()) {
            ScriptAnalysisResult result = analyzeScript(task, workflow);
            results.add(result);
            diagnostics.addAll(result.getDiagnostics().getDiagnostics());
        }
        return new WorkflowBatchResult(request.getWorkflowId(), results, metadata.create(workflow).manifest(), new DiagnosticBag(diagnostics));
    }

    private static final class StatementRun {
        private final StatementAnalysisResult result;
        private final SessionSnapshot session;

        private StatementRun(StatementAnalysisResult result, SessionSnapshot session) {
            this.result = result;
            this.session = session;
        }
    }

    private static final class StageFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        private StageFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
