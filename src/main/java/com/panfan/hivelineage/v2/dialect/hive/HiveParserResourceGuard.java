package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.syntax.SyntaxOptions;

import java.util.Optional;

/**
 * Runs before the native recursive parser, using Token structure only.
 */
final class HiveParserResourceGuard {
    Optional<Diagnostic> check(LexedUnit unit, SyntaxOptions options) {
        if (unit.getTokens().size() > options.getMaximumTokenCount()) {
            return Optional.of(new Diagnostic(AnalysisStage.SYNTAX, "PARSER_TOKEN_LIMIT",
                    "Token count exceeds the parser resource limit", unit.getExpandedUnit().getOriginalUnit().getSourceRef()));
        }
        int depth = 0;
        for (LexToken token : unit.getTokens()) {
            if (token.getChannel() != TokenChannel.DEFAULT) {
                continue;
            }
            String spelling = token.getLexeme();
            if ("(".equals(spelling) || "[".equals(spelling)) {
                depth++;
            } else if (")".equals(spelling) || "]".equals(spelling)) {
                depth--;
            }
            if (depth > options.getMaximumNestingDepth()) {
                return Optional.of(new Diagnostic(AnalysisStage.SYNTAX, "PARSER_NESTING_LIMIT",
                        "Token nesting exceeds the parser resource limit", token.getSourceRef()));
            }
        }
        return Optional.empty();
    }
}
