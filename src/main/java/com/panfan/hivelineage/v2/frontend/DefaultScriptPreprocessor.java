package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import java.util.ArrayList;
import java.util.List;

/** Preserves raw SQL and source positions; comments are handled as lexer trivia. */
public final class DefaultScriptPreprocessor implements ScriptPreprocessor {
    @Override
    public ScriptPreprocessResult preprocess(String sourceId, String script, ScriptOptions options) {
        List<ScriptUnit> units = new ArrayList<ScriptUnit>();
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        ScriptUnitReader reader = new DefaultScriptUnitReader();
        ScriptCursor cursor = new ScriptCursor(0, 0);
        while (cursor.getOffset() < script.length()) {
            ScriptReadResult result = reader.readNext(sourceId, script, cursor, options);
            diagnostics.addAll(result.getDiagnostics());
            if (result.getUnit() != null) {
                units.add(result.getUnit());
            }
            cursor = result.getNextCursor();
            if (options.getMaximumUnitCount() > 0 && units.size() > options.getMaximumUnitCount()) {
                diagnostics.add(new Diagnostic(AnalysisStage.FRONTEND, "SCRIPT_UNIT_LIMIT",
                        "Script contains more units than the configured limit", SourceRef.synthetic(sourceId)));
                return new ScriptPreprocessResult(units, diagnostics, StageOutcome.FAILED);
            }
            if (result.isEndOfScript()) {
                break;
            }
        }
        return new ScriptPreprocessResult(units, diagnostics, diagnostics.isEmpty() ? StageOutcome.SUCCESS : StageOutcome.FAILED);
    }
}
