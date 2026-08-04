package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.LineageWarning;
import java.util.List;
import java.util.Map;

final class StatementAnalysisContext {
    private final LineageAnalyzeRequest request;
    private final String rawStatement;
    private final String normalizedStatement;
    private final int statementIndex;
    private final ScriptAnalysisState scriptState;
    private final List<LineageWarning> warnings;

    StatementAnalysisContext(
        LineageAnalyzeRequest request,
        String rawStatement,
        String normalizedStatement,
        int statementIndex,
        ScriptAnalysisState scriptState,
        List<LineageWarning> warnings
    ) {
        this.request = request;
        this.rawStatement = rawStatement;
        this.normalizedStatement = normalizedStatement;
        this.statementIndex = statementIndex;
        this.scriptState = scriptState;
        this.warnings = warnings;
    }

    LineageAnalyzeRequest getRequest() {
        return request;
    }

    String getRawStatement() {
        return rawStatement;
    }

    String getNormalizedStatement() {
        return normalizedStatement;
    }

    int getStatementIndex() {
        return statementIndex;
    }

    Map<String, List<DataObjectRef>> getTemporaryTableSources() {
        return scriptState.getTemporaryTableSources();
    }

    Map<String, Map<String, ColumnLineageProjection>> getTemporaryTableColumns() {
        return scriptState.getTemporaryTableColumns();
    }

    Map<String, Map<String, ColumnLineageProjection>> getViewColumnLineageCache() {
        return scriptState.getViewColumnLineageCache();
    }

    List<LineageWarning> getWarnings() {
        return warnings;
    }
}
