package com.panfan.hivelineage.v2.frontend;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;

import java.util.Collections;
import java.util.List;

/**
 * Quote-aware script boundaries are found before the current statement is expanded.
 */
public final class DefaultScriptUnitReader implements ScriptUnitReader {
    @Override
    public ScriptReadResult readNext(String sourceId, String script, ScriptCursor cursor, ScriptOptions options) {
        int offset = cursor.getOffset();
        while (offset < script.length()) {
            Boundary boundary = boundary(script, offset, options.getDelimiter());
            String text = script.substring(offset, boundary.end);
            SourceRef source = new SourceRef(sourceId, new SourceSpan(sourceId, offset, boundary.end));
            List<Diagnostic> diagnostics = boundary.unterminated
                    ? Collections.singletonList(new Diagnostic(AnalysisStage.FRONTEND, "UNTERMINATED_SCRIPT_UNIT",
                    "A quoted value or block comment has no closing delimiter", source))
                    : Collections.<Diagnostic>emptyList();
            if (boundary.hasCode) {
                ScriptUnit unit = new ScriptUnit(sourceId, cursor.getStatementIndex(), text, ScriptUnitKind.SQL, source);
                return new ScriptReadResult(unit, new ScriptCursor(boundary.next, cursor.getStatementIndex() + 1),
                        boundary.next >= script.length(), diagnostics);
            }
            offset = boundary.next;
        }
        return new ScriptReadResult(null, new ScriptCursor(script.length(), cursor.getStatementIndex()), true,
                Collections.<Diagnostic>emptyList());
    }

    private Boundary boundary(String script, int start, String delimiter) {
        int position = start;
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean hasCode = false;
        while (position < script.length()) {
            char current = script.charAt(position);
            char next = position + 1 < script.length() ? script.charAt(position + 1) : 0;
            if (lineComment) {
                lineComment = current != '\n' && current != '\r';
                position++;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    position++;
                }
                position++;
                continue;
            }
            if (quote != 0) {
                position = advanceQuoted(script, position, quote);
                if (position < 0) {
                    quote = 0;
                    position = -position;
                }
                continue;
            }
            if (script.startsWith(delimiter, position)) {
                return new Boundary(position, position + delimiter.length(), hasCode, false);
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                position += 2;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                position += 2;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            }
            hasCode = hasCode || !Character.isWhitespace(current);
            position++;
        }
        return new Boundary(position, position, hasCode, quote != 0 || blockComment);
    }

    private int advanceQuoted(String script, int position, char quote) {
        char current = script.charAt(position);
        if (current == '\\' && position + 1 < script.length()) {
            return position + 2;
        }
        if (current != quote) {
            return position + 1;
        }
        if (position + 1 < script.length() && script.charAt(position + 1) == quote) {
            return position + 2;
        }
        return -(position + 1);
    }

    private static final class Boundary {
        private final int end;
        private final int next;
        private final boolean hasCode;
        private final boolean unterminated;

        private Boundary(int end, int next, boolean hasCode, boolean unterminated) {
            this.end = end;
            this.next = next;
            this.hasCode = hasCode;
            this.unterminated = unterminated;
        }
    }
}
