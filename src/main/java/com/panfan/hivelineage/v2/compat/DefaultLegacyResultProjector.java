package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageWarning;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.AnalysisBasis;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementStatus;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.export.DefaultSourceRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Projects declared facts and renders source attachments; no Parser, Binder or metadata call. */
public final class DefaultLegacyResultProjector implements LegacyResultProjector {
    @Override
    public LegacyProjection project(ScriptAnalysisResult result) {
        List<ProjectionLimitation> limitations = new ArrayList<ProjectionLimitation>();
        Optional<AnalysisContract> contract = result.getBasis().map(AnalysisBasis::getContract);
        LegacyStatementProjector projector = new LegacyStatementProjector(new DefaultSourceRenderer(result.getSources()), contract, limitations);
        List<StatementLineageResult> statements = new ArrayList<StatementLineageResult>();
        List<LineageWarning> warnings = new ArrayList<LineageWarning>();
        for (StatementAnalysisResult statement : result.getStatements()) {
            StatementLineageResult projected = projector.project(statement);
            warnings.addAll(projected.getWarnings());
            if (statement.getStatus() != StatementStatus.IGNORED || contract.map(AnalysisContract::isIncludeIgnoredStatements).orElse(true)
                    || !projected.getWarnings().isEmpty()) {
                statements.add(projected);
            }
        }
        LineageAnalyzeResult legacy = LineageAnalyzeResult.builder().workflowId(result.getTask().getWorkflowId())
                .taskId(result.getTask().getTaskId()).taskVersion(result.getTask().getTaskVersion()).statements(statements)
                .totalStatementCount(result.getCounts().getTotal()).ignoredStatementCount(result.getCounts().getIgnored())
                .failedStatementCount(result.getCounts().getFailed()).warnings(warnings).build();
        return new LegacyProjection(legacy, limitations);
    }
}
