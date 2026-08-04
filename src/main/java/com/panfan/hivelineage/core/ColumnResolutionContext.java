package com.panfan.hivelineage.core;

import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageWarning;
import java.util.List;

final class ColumnResolutionContext {
    private final LineageAnalyzeRequest request;
    private final LineageParseMode parseMode;
    private final List<LineageWarning> warnings;
    private final int statementIndex;

    ColumnResolutionContext(
        LineageAnalyzeRequest request,
        LineageParseMode parseMode,
        List<LineageWarning> warnings,
        int statementIndex
    ) {
        this.request = request;
        this.parseMode = parseMode;
        this.warnings = warnings;
        this.statementIndex = statementIndex;
    }

    LineageAnalyzeRequest getRequest() {
        return request;
    }

    LineageParseMode getParseMode() {
        return parseMode;
    }

    List<LineageWarning> getWarnings() {
        return warnings;
    }

    int getStatementIndex() {
        return statementIndex;
    }
}
