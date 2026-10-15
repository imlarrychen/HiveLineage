package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Token-level grammar extensions preserve the original immutable token buffer. */
final class HiveGrammarExtensions {
    static Set<Integer> cteColumnTokens(LexedUnit unit) {
        return cteColumnTokens(visible(unit));
    }

    static Set<Integer> cteColumnTokens(List<LexToken> tokens) {
        Set<Integer> hidden = new HashSet<Integer>();
        for (int index = 0; index < tokens.size(); index++) {
            if ("with".equalsIgnoreCase(tokens.get(index).getLexeme())) {
                cteColumns(tokens, index + 1, hidden);
            }
        }
        return hidden;
    }

    private static void cteColumns(List<LexToken> tokens, int index, Set<Integer> hidden) {
        while (index + 3 < tokens.size() && tokens.get(index).getKind() == TokenKind.IDENTIFIER) {
            int alias = index++;
            int columnsEnd = "(".equals(tokens.get(index).getLexeme()) ? simpleNamesEnd(tokens, index + 1) : -1;
            int as = columnsEnd < 0 ? index : columnsEnd + 1;
            if (as + 1 >= tokens.size() || !"as".equalsIgnoreCase(tokens.get(as).getLexeme())
                    || !"(".equals(tokens.get(as + 1).getLexeme())) {
                return;
            }
            if (columnsEnd >= 0) {
                for (int column = alias + 1; column <= columnsEnd; column++) {
                    hidden.add(tokens.get(column).getIndex());
                }
            }
            int close = matchingClose(tokens, as + 1);
            if (close < 0 || close + 1 >= tokens.size() || !",".equals(tokens.get(close + 1).getLexeme())) {
                return;
            }
            index = close + 2;
        }
    }

    private static int matchingClose(List<LexToken> tokens, int start) {
        int depth = 0;
        for (int index = start; index < tokens.size(); index++) {
            String value = tokens.get(index).getLexeme();
            if ("(".equals(value)) {
                depth++;
            } else if (")".equals(value) && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int simpleNamesEnd(List<LexToken> tokens, int start) {
        boolean name = true;
        int index = start;
        while (index < tokens.size()) {
            LexToken token = tokens.get(index);
            if (!name && ")".equals(token.getLexeme())) {
                return index;
            }
            if (name && token.getKind() != TokenKind.IDENTIFIER) {
                return -1;
            }
            if (!name && !",".equals(token.getLexeme())) {
                return -1;
            }
            name = !name;
            index++;
        }
        return -1;
    }

    static Set<Integer> unknownProjectionTokens(LexedUnit unit) {
        return unknownProjectionTokens(visible(unit));
    }

    static Set<Integer> unknownProjectionTokens(List<LexToken> tokens) {
        Set<Integer> unknown = new HashSet<Integer>();
        Set<Integer> projectionDepths = new HashSet<Integer>();
        int depth = 0;
        for (LexToken token : tokens) {
            String value = token.getLexeme();
            if ("(".equals(value)) {
                depth++;
            } else if (")".equals(value)) {
                projectionDepths.remove(depth--);
            } else if ("select".equalsIgnoreCase(value)) {
                projectionDepths.add(depth);
            } else if ("from".equalsIgnoreCase(value)) {
                projectionDepths.remove(depth);
            } else if (token.getKind() == TokenKind.UNKNOWN && projectionDepths.contains(depth) && questionMarks(value)) {
                unknown.add(token.getIndex());
            }
        }
        return unknown;
    }

    static Set<Integer> quotedSelectAliases(LexedUnit unit) {
        return quotedSelectAliases(visible(unit));
    }

    static Set<Integer> quotedSelectAliases(List<LexToken> tokens) {
        Set<Integer> aliases = new HashSet<Integer>();
        Set<Integer> projections = new HashSet<Integer>();
        int depth = 0;
        String previous = "";
        for (LexToken token : tokens) {
            String text = token.getLexeme();
            if ("(".equals(text)) {
                depth++;
            } else if (")".equals(text)) {
                projections.remove(depth--);
            } else if ("select".equalsIgnoreCase(text)) {
                projections.add(depth);
            } else if ("from".equalsIgnoreCase(text)) {
                projections.remove(depth);
            } else if (token.getKind() == TokenKind.STRING_LITERAL && "as".equalsIgnoreCase(previous)
                    && projections.contains(depth)) {
                aliases.add(token.getIndex());
            }
            previous = text;
        }
        return aliases;
    }

    private static boolean questionMarks(String text) {
        for (char character : text.trim().toCharArray()) {
            if (character != '?') {
                return false;
            }
        }
        return !text.trim().isEmpty();
    }

    static List<LexToken> visible(LexedUnit unit) {
        List<LexToken> result = new ArrayList<LexToken>();
        for (LexToken token : unit.getTokens()) {
            if (token.getChannel() == TokenChannel.DEFAULT) {
                result.add(token);
            }
        }
        return result;
    }
}
