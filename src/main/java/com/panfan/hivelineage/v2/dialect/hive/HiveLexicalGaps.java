package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.lexical.DialectSymbol;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.lexical.TokenLocation;

import java.util.List;

/**
 * Retains characters skipped by a recovering native lexer as explicit unknown lexical input.
 */
final class HiveLexicalGaps {
    static void append(List<LexToken> tokens, ExpandedUnit unit, int start, int end) {
        if (start >= end) {
            return;
        }
        String text = unit.getExpandedText().substring(start, end);
        if (text.trim().isEmpty()) {
            return;
        }
        boolean comment = text.trim().startsWith("--") || text.trim().startsWith("/*");
        SourceRef source = new SourceRef(unit.getExpandedSourceId(), new SourceSpan(unit.getExpandedSourceId(), start, end));
        tokens.add(new LexToken(tokens.size(), TokenKind.UNKNOWN, text, null,
                comment ? TokenChannel.HIDDEN : TokenChannel.DEFAULT,
                new TokenLocation(source, new DialectSymbol("hive", "UNKNOWN"))));
    }
}
