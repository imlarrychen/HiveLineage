package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.analysis.JoinFacts;
import com.panfan.hivelineage.v2.analysis.LineageFacts;
import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ResourceLimitException;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.frontend.DefaultScriptPreprocessor;
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
import com.panfan.hivelineage.v2.metadata.WorkflowMetadataAccess;
import com.panfan.hivelineage.v2.metadata.WorkflowMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.BudgetedMetadataAccess;
import com.panfan.hivelineage.v2.metadata.BudgetedScriptSourceProvider;
import com.panfan.hivelineage.v2.metadata.ExternalReadBudget;
import com.panfan.hivelineage.v2.metadata.MetadataAccess;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefinitionTextSourceProvider;
import com.panfan.hivelineage.v2.metadata.ScriptSourceProvider;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.BindingDemand;
import com.panfan.hivelineage.v2.semantic.BindingOutcome;
import com.panfan.hivelineage.v2.semantic.BindingResult;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;
import com.panfan.hivelineage.v2.semantic.DefaultViewTemplateCache;
import com.panfan.hivelineage.v2.semantic.ViewTemplateCache;
import com.panfan.hivelineage.v2.semantic.PreparedDefinition;
import com.panfan.hivelineage.v2.semantic.SemanticBinder;
import com.panfan.hivelineage.v2.semantic.VerificationResult;
import com.panfan.hivelineage.v2.session.SessionSnapshot;
import com.panfan.hivelineage.v2.session.VariableKey;
import com.panfan.hivelineage.v2.syntax.SyntaxResult;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage composition and request lifecycle; semantic rules remain in their own components.
 */
public final class DefaultScriptLineageAnalyzer implements ScriptLineageAnalyzer, WorkflowBatchAnalyzer {
    private final SyntaxFrontend frontend;
    private final WorkflowMetadataAccessFactory metadata;
    private final AnalyzerServices services;
    private final DefinitionBindingCoordinator bindingCoordinator;
    private final com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor;
    private final ViewTemplateCache templates;
    private final ScriptSourceProvider sourceProvider;

    public DefaultScriptLineageAnalyzer() {
        this(new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()), new DefaultSemanticBinder(), new DefaultMetadataAccessFactory());
    }

    /**
     * Compatibility constructor; stage execution depends on capabilities.
     */
    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata) {
        this(frontend, binder, (WorkflowMetadataAccessFactory) metadata);
    }

    /**
     * Compatibility constructor; stage execution depends on capabilities.
     */
    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor) {
        this(frontend, binder, (WorkflowMetadataAccessFactory) metadata, preprocessor);
    }

    /**
     * Compatibility constructor; stage execution depends on capabilities.
     */
    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates) {
        this(frontend, binder, (WorkflowMetadataAccessFactory) metadata, preprocessor, templates);
    }

    /**
     * Compatibility constructor; stage execution depends on capabilities.
     */
    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, DefaultMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates, ScriptSourceProvider sourceProvider) {
        this(frontend, binder, (WorkflowMetadataAccessFactory) metadata, preprocessor, templates, sourceProvider);
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, WorkflowMetadataAccessFactory metadata) {
        this(frontend, binder, metadata, new DefaultScriptPreprocessor());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, WorkflowMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor) {
        this(frontend, binder, metadata, preprocessor, new DefaultViewTemplateCache());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, WorkflowMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates) {
        this(frontend, binder, metadata, preprocessor, templates, new DefinitionTextSourceProvider());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, WorkflowMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates,
                                        ScriptSourceProvider sourceProvider) {
        this(frontend, binder, metadata, preprocessor, templates, sourceProvider, AnalyzerServices.defaults());
    }

    public DefaultScriptLineageAnalyzer(SyntaxFrontend frontend, SemanticBinder binder, WorkflowMetadataAccessFactory metadata,
                                        com.panfan.hivelineage.v2.frontend.ScriptPreprocessor preprocessor, ViewTemplateCache templates,
                                        ScriptSourceProvider sourceProvider, AnalyzerServices services) {
        this.services = java.util.Objects.requireNonNull(services, "services");
        this.bindingCoordinator = new DefinitionBindingCoordinator(binder, services.functions);
        this.sourceProvider = java.util.Objects.requireNonNull(sourceProvider, "sourceProvider");
        this.templates = java.util.Objects.requireNonNull(templates, "templates");
        this.preprocessor = java.util.Objects.requireNonNull(preprocessor, "preprocessor");
        this.frontend = java.util.Objects.requireNonNull(frontend, "frontend");
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
        WorkflowMetadataAccess captured = metadata.create(workflow);
        ExternalReadBudget readBudget = new ExternalReadBudget(request.getResourceLimits().getMaxExternalReads());
        MetadataAccess access = new BudgetedMetadataAccess(captured, readBudget);
        Map<String, SourceDocument> sources = new LinkedHashMap<>();
        sources.put(request.getSource().getSourceId(), request.getSource());
        DefaultDefinitionCoordinator definitions = new DefaultDefinitionCoordinator(frontend,
                new BudgetedScriptSourceProvider(sourceProvider, readBudget), templates, sources, request.getResourceLimits(), preprocessor);
        Map<DefinitionId, PreparedDefinition> prepared = new LinkedHashMap<>();
        Map<DefinitionId, DiagnosticBag> failedDefinitions = new LinkedHashMap<>();
        SessionSnapshot session = SessionSnapshot.initial(request.getInitialSession());
        ScriptPreprocessResult script = preprocessor.preprocess(request.getSource().getSourceId(), request.getSource().getText(), ScriptOptions.defaults());
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
        return new ScriptAnalysisResult(request.getTask(), EngineIdentity.v2(request.getDialect().getSemanticRuleVersion()),
                statements, new StatementCounts(script.getUnits().size(), ignored, failed), new DiagnosticBag(diagnostics), Optional.of(basis), sources, frontendEvidence, versions);
    }

    private StatementRun analyzeStatement(ScriptAnalysisRequest request, ScriptUnit raw, SessionSnapshot session,
                                          MetadataAccess access, com.panfan.hivelineage.v2.session.DefinitionCoordinator<PreparedDefinition> coordinator, Map<DefinitionId, PreparedDefinition> prepared,
                                          Map<DefinitionId, DiagnosticBag> failedDefinitions, Map<String, SourceDocument> sources) {
        int index = raw.getStatementIndex();
        TaskIdentity task = request.getTask();
        StatementIdentity identity = StatementIdentity.forTask(task, index);
        SourceDocument rawSql = new SourceDocument(raw.getSourceId(), raw.getRawText().trim());
        SourceDocument normalized = rawSql;
        List<Diagnostic> diagnostics = new ArrayList<>();
        String type = "UNSUPPORTED";
        AnalysisStage stage = AnalysisStage.FRONTEND;
        Optional<ExpandedUnit> frontendEvidence = Optional.empty();
        try {
            AnalysisContract contract = request.getContract();
            ExpandedUnit expanded = expand(request, raw, session, diagnostics);
            frontendEvidence = Optional.of(expanded);
            if (expanded.getExpandedText().length() > request.getResourceLimits().getMaxScriptCharacters()) {
                throw new ResourceLimitException("Expanded statement exceeds configured source size");
            }
            normalized = new SourceDocument(expanded.getExpandedSourceId(), expanded.getExpandedText());
            sources.put(normalized.getSourceId(), normalized);
            stage = AnalysisStage.SYNTAX;
            SyntaxStatement syntax = parse(request, expanded, diagnostics);
            StatementClassification classification = StatementClassification.of(syntax, contract);
            type = classification.type;
            validateSyntax(syntax, classification);
            StatementPublicationDecision publication = new StatementPublicationDecision(syntax, contract, classification);
            if (publication.ignoreBeforeBinding()) {
                return new StatementRun(new StatementAnalysisResult(identity, type, rawSql, normalized, StatementStatus.IGNORED, false,
                        Optional.<LineageFacts>empty(), Optional.<JoinFacts>empty(), Optional.<BoundAnalysisUnit>empty(), Optional.ofNullable(classification.skipReason),
                        Optional.<String>empty(), Optional.<String>empty(), new DiagnosticBag(diagnostics), frontendEvidence), session);
            }
            BindingDemand demand = services.demands.plan(syntax, contract);
            stage = AnalysisStage.BINDING;
            BindingResult binding = bindingCoordinator.bind(request, syntax, demand, session, access, coordinator, prepared, failedDefinitions);
            diagnostics.addAll(binding.getDiagnostics().getDiagnostics());
            BoundAnalysisUnit unit = requireBoundUnit(binding);
            IrResourceGuard resources = new IrResourceGuard();
            resources.checkUnit(unit, request.getResourceLimits());
            stage = AnalysisStage.VERIFICATION;
            VerificationResult verification = services.verifier.verify(unit, demand);
            diagnostics.addAll(verification.getDiagnostics().getDiagnostics());
            if (!verification.getVerifiedUnit().isPresent()) {
                throw new AnalysisFailure(PipelineDiagnosticCode.IR_VERIFICATION_FAILED, "Bound IR failed structural or binding verification");
            }
            VerifiedAnalysisUnit verified = verification.getVerifiedUnit().get();
            stage = AnalysisStage.ANALYSIS;
            Optional<LineageFacts> lineage = Optional.empty();
            boolean formal = publication.formal(unit);
            if (publication.analyzeLineage(unit)) {
                lineage = Optional.of(services.lineage.apply(request.getResourceLimits().getMaxEvidenceEdges()).analyze(verified, services.rules));
                diagnostics.addAll(lineage.get().getDiagnostics().getDiagnostics());
                resources.checkFacts(lineage.get(), request.getResourceLimits());
            }
            Optional<JoinFacts> joins = Optional.empty();
            if (contract.getCapabilities().supports(AnalysisCapability.JOIN_CONDITION)) {
                joins = Optional.of(services.joins.apply(request.getResourceLimits().getMaxEvidenceEdges()).analyze(verified, services.rules));
                diagnostics.addAll(joins.get().getDiagnostics().getDiagnostics());
                resources.checkJoinFacts(joins.get(), request.getResourceLimits());
            }
            boolean published = publication.published(formal, lineage, joins);
            StatementStatus status = !published ? StatementStatus.IGNORED : binding.getOutcome() == BindingOutcome.PARTIAL ? StatementStatus.PARTIAL_SUCCESS : StatementStatus.SUCCESS;
            com.panfan.hivelineage.v2.session.SessionCommitResult committed = services.committer.commit(session,
                    services.effects.plan(session, unit).withDefinitions(SyntaxDefinitionTemplates.prepare(syntax, unit)));
            diagnostics.addAll(committed.getDiagnostics().getDiagnostics());
            SessionSnapshot next = committed.getSnapshot();
            if (published && committed.getOutcome() == com.panfan.hivelineage.v2.session.CommitOutcome.UNCERTAIN) {
                status = StatementStatus.PARTIAL_SUCCESS;
            }
            return new StatementRun(new StatementAnalysisResult(identity, type, rawSql, normalized, status, formal, lineage, joins, Optional.of(unit),
                    published ? Optional.<String>empty() : Optional.ofNullable(classification.skipReason), Optional.<String>empty(), Optional.<String>empty(), new DiagnosticBag(diagnostics), frontendEvidence), next);
        } catch (RuntimeException failure) {
            String code = failure instanceof ResourceLimitException ? PipelineDiagnosticCode.RESOURCE_LIMIT.getValue()
                    : failure instanceof AnalysisFailure ? ((AnalysisFailure) failure).code : stage.name() + "_ERROR";
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            diagnostics.add(new Diagnostic(stage, code, message, raw.getSourceRef()));
            return new StatementRun(new StatementAnalysisResult(identity, type, rawSql, normalized, StatementStatus.FAILED, false,
                    Optional.<LineageFacts>empty(), Optional.<JoinFacts>empty(), Optional.<BoundAnalysisUnit>empty(), Optional.<String>empty(),
                    Optional.of(code), Optional.of(message), new DiagnosticBag(DiagnosticBag.distinct(diagnostics)), frontendEvidence, Optional.of(failure)), session);
        }
    }

    private ExpandedUnit expand(ScriptAnalysisRequest request, ScriptUnit raw, SessionSnapshot session,
                                List<Diagnostic> diagnostics) {
        AnalysisContract contract = request.getContract();
        ExpansionResult expansion = services.variables.expand(raw, variables(session, contract), new VariableOptions(
                contract.getVariablePolicy() == VariablePolicy.CURRENT_API_FALLBACK ? VariableResolutionPolicy.LEGACY_FALLBACK : contract.getVariablePolicy() == VariablePolicy.SPEC_VARIABLES
                                                                                                                                 ? VariableResolutionPolicy.SPEC_VARIABLES : VariableResolutionPolicy.PRESERVE_AND_WARN,
                contract.getFallbackVariableValue(), request.getResourceLimits().getMaxScriptCharacters()));
        diagnostics.addAll(expansion.getDiagnostics());
        if (expansion.getUnit() == null) {
            if (expansion.getDiagnostics().stream().anyMatch(diagnostic -> "EXPANSION_SIZE_LIMIT".equals(diagnostic.getCode()))) {
                throw new ResourceLimitException("Expanded statement exceeds configured source size");
            }
            throw new AnalysisFailure(PipelineDiagnosticCode.VARIABLE_EXPANSION_FAILED, "Variables could not be expanded");
        }
        return expansion.getUnit();
    }

    private SyntaxStatement parse(ScriptAnalysisRequest request, ExpandedUnit expanded, List<Diagnostic> diagnostics) {
        SyntaxResult parsed = frontend.parse(expanded, request.getDialect());
        diagnostics.addAll(parsed.getDiagnostics());
        if (parsed.getStatement() == null) {
            throw new AnalysisFailure(PipelineDiagnosticCode.SQL_PARSE_FAILED, "Independent frontend did not produce a syntax statement");
        }
        return parsed.getStatement();
    }

    private void validateSyntax(SyntaxStatement syntax, StatementClassification classification) {
        String type = classification.type;
        if (syntax instanceof com.panfan.hivelineage.v2.syntax.OtherStatementSyntax
                && "INVALID_USE_DATABASE".equals(((com.panfan.hivelineage.v2.syntax.OtherStatementSyntax) syntax).getUnsupportedReason())) {
            throw new AnalysisFailure(PipelineDiagnosticCode.INVALID_USE_DATABASE, "USE requires one unquoted or backtick-quoted database identifier");
        }
        if (syntax instanceof com.panfan.hivelineage.v2.syntax.OtherStatementSyntax && classification.formalCandidate) {
            throw new AnalysisFailure(PipelineDiagnosticCode.UNSUPPORTED_STATEMENT, "Data-changing statement " + type + " has no lineage binding");
        }
    }

    private BoundAnalysisUnit requireBoundUnit(BindingResult binding) {
        if (!binding.getUnit().isPresent() || binding.getOutcome() == BindingOutcome.FAILED || binding.getOutcome() == BindingOutcome.UNSUPPORTED) {
            List<Diagnostic> bindingDiagnostics = binding.getDiagnostics().getDiagnostics();
            Diagnostic primary = bindingDiagnostics.isEmpty() ? null : bindingDiagnostics.get(bindingDiagnostics.size() - 1);
            throw new AnalysisFailure(binding.getOutcome() == BindingOutcome.UNSUPPORTED ? "UNSUPPORTED_STATEMENT"
                    : primary == null ? "BINDING_FAILED" : primary.getCode(),
                    primary == null ? "Statement cannot be bound for requested capabilities" : primary.getMessage());
        }
        return binding.getUnit().get();
    }

    private static VariableContext variables(SessionSnapshot session, AnalysisContract contract) {
        Map<String, Map<String, String>> namespaces = new LinkedHashMap<>();
        session.getVariables().forEach((key, value) -> {
            if (value.getValue().isPresent()) {
                String namespace = key.getNamespace().getQualifier();
                namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
                if (key.getNamespace() == VariableKey.Namespace.UNQUALIFIED) {
                    namespaces.computeIfAbsent("hiveconf", ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
                }
            }
        });
        if (contract.getVariablePolicy() == VariablePolicy.CURRENT_API_FALLBACK) {
            session.getVariables().forEach((key, value) -> {
                if (key.getNamespace() == VariableKey.Namespace.UNQUALIFIED && value.getValue().isPresent()) {
                    for (String namespace : new String[]{"hiveconf", "hivevar", "runtime"}) {
                        namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).put(key.getName(), value.getValue().get());
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

}
