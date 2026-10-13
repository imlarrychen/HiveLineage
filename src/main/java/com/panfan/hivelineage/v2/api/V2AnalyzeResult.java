package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.common.ModelChecks;
import com.panfan.hivelineage.v2.common.ModelCollections;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Collections;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;

public final class V2AnalyzeResult {
    private final TaskIdentity task;
    private final EngineIdentity engine;
    private final List<StatementAnalysisResult> statements;
    private final StatementCounts counts;
    private final DiagnosticBag diagnostics;
    private final Optional<AnalysisBasis> basis;
    private final Map<String, SourceDocument> sources;
    private final Map<String, ExpandedUnit> frontendEvidence;
    private final List<DatasetVersionRecord> datasetVersions;

    public V2AnalyzeResult(TaskIdentity task, EngineIdentity engine, List<StatementAnalysisResult> statements, StatementCounts counts, DiagnosticBag diagnostics) {
        this(task, engine, statements, counts, diagnostics, Optional.<AnalysisBasis>empty());
    }

    public V2AnalyzeResult(TaskIdentity task, EngineIdentity engine, List<StatementAnalysisResult> statements, StatementCounts counts, DiagnosticBag diagnostics, Optional<AnalysisBasis> basis) {
        this(task, engine, statements, counts, diagnostics, basis, Collections.<String, SourceDocument>emptyMap());
    }

    public V2AnalyzeResult(TaskIdentity task, EngineIdentity engine, List<StatementAnalysisResult> statements, StatementCounts counts, DiagnosticBag diagnostics, Optional<AnalysisBasis> basis, Map<String, SourceDocument> sources) {
        this(task, engine, statements, counts, diagnostics, basis, sources, Collections.<String, ExpandedUnit>emptyMap());
    }

    public V2AnalyzeResult(TaskIdentity task, EngineIdentity engine, List<StatementAnalysisResult> statements, StatementCounts counts, DiagnosticBag diagnostics, Optional<AnalysisBasis> basis, Map<String, SourceDocument> sources, Map<String, ExpandedUnit> frontendEvidence) {
        this(task, engine, statements, counts, diagnostics, basis, sources, frontendEvidence, Collections.<DatasetVersionRecord>emptyList());
    }

    public V2AnalyzeResult(TaskIdentity task, EngineIdentity engine, List<StatementAnalysisResult> statements, StatementCounts counts, DiagnosticBag diagnostics, Optional<AnalysisBasis> basis, Map<String, SourceDocument> sources, Map<String, ExpandedUnit> frontendEvidence, List<DatasetVersionRecord> datasetVersions) {
        this.sources = ModelCollections.immutableMap(sources);
        this.frontendEvidence = ModelCollections.immutableMap(frontendEvidence);
        this.datasetVersions = ModelCollections.immutableList(datasetVersions);
        this.task = ModelChecks.value(task, "task");
        this.engine = ModelChecks.value(engine, "engine");
        this.statements = ModelCollections.immutableList(statements);
        this.counts = ModelChecks.value(counts, "counts");
        this.diagnostics = ModelChecks.value(diagnostics, "diagnostics");
        this.basis = ModelChecks.value(basis, "basis");
    }

    public Map<String, SourceDocument> getSources() {
        return sources;
    }

    public Map<String, ExpandedUnit> getFrontendEvidence() {
        return frontendEvidence;
    }

    public List<DatasetVersionRecord> getDatasetVersions() {
        return datasetVersions;
    }

    public TaskIdentity getTask() {
        return task;
    }

    public EngineIdentity getEngine() {
        return engine;
    }

    public List<StatementAnalysisResult> getStatements() {
        return statements;
    }

    public StatementCounts getCounts() {
        return counts;
    }

    public DiagnosticBag getDiagnostics() {
        return diagnostics;
    }

    public Optional<AnalysisBasis> getBasis() {
        return basis;
    }
}
