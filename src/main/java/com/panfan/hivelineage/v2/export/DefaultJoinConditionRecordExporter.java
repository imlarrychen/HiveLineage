package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.JoinFact;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import com.panfan.hivelineage.v2.api.SourceDocument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DefaultJoinConditionRecordExporter implements JoinConditionRecordExporter {
    private final SourceRenderer renderer;

    public DefaultJoinConditionRecordExporter() {
        this.renderer = null;
    }

    public DefaultJoinConditionRecordExporter(SourceRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public List<JoinConditionRecord> export(StatementAnalysisResult statement) {
        List<JoinConditionRecord> records = new ArrayList<JoinConditionRecord>();
        if (statement.getJoins().isPresent()) {
            SourceRenderer sources = renderer == null ? statementSources(statement) : renderer;
            BoundNameIndex names = new BoundNameIndex(statement.getBoundUnit().get());
            BoundExpressionFormatter expressions = new BoundExpressionFormatter(statement.getBoundUnit().get(), names, sources);
            JoinPresentationProjector presentations = new JoinPresentationProjector(names, expressions, sources);
            for (JoinFact fact : statement.getJoins().get().getJoins()) {
                records.add(new JoinConditionRecord(statement.getIdentity(), statement.getRawSql(), fact,
                        Optional.of(presentations.project(fact))));
            }
        }
        return Collections.unmodifiableList(records);
    }

    private SourceRenderer statementSources(StatementAnalysisResult statement) {
        Map<String, SourceDocument> sources = new LinkedHashMap<String, SourceDocument>();
        sources.put(statement.getRawSql().getSourceId(), statement.getRawSql());
        sources.put(statement.getNormalizedSql().getSourceId(), statement.getNormalizedSql());
        return new DefaultSourceRenderer(sources);
    }
}
