package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.Set;

/**
 * One immutable source-position index shared by normalization queries.
 */
final class HiveTokenIndex {
    private final List<LexToken> visible;
    private final Map<String, List<Integer>> symbols = new HashMap<String, List<Integer>>();
    private final NavigableMap<Integer, InsertModeSyntax> insertModes = new TreeMap<Integer, InsertModeSyntax>();
    private Set<Integer> cteColumns;
    private Set<Integer> recoveredProjections;
    private Set<Integer> quotedAliases;
    private Set<Integer> overwriteColumns;

    HiveTokenIndex(LexedUnit unit) {
        List<LexToken> visibleTokens = new ArrayList<LexToken>();
        boolean afterInsert = false;
        for (LexToken token : unit.getTokens()) {
            String symbol = token.getDialectSymbol().getSymbolName();
            List<Integer> positions = symbols.get(symbol);
            if (positions == null) {
                positions = new ArrayList<Integer>();
                symbols.put(symbol, positions);
            }
            positions.add(token.getSourceRef().getSpan().getStart());
            if (token.getChannel() != TokenChannel.DEFAULT || token.getKind() == TokenKind.END_OF_INPUT) {
                continue;
            }
            visibleTokens.add(token);
            if ("insert".equalsIgnoreCase(token.getLexeme())) {
                afterInsert = true;
            } else if (afterInsert && ("into".equalsIgnoreCase(token.getLexeme())
                    || "overwrite".equalsIgnoreCase(token.getLexeme()))) {
                insertModes.put(token.getSourceRef().getSpan().getStart(), "into".equalsIgnoreCase(token.getLexeme())
                        ? InsertModeSyntax.INTO : InsertModeSyntax.OVERWRITE);
                afterInsert = false;
            }
        }
        visible = Collections.unmodifiableList(visibleTokens);
    }

    List<LexToken> visible() {
        return visible;
    }

    Set<Integer> cteColumnTokens() {
        if (cteColumns == null) {
            cteColumns = Collections.unmodifiableSet(HiveGrammarExtensions.cteColumnTokens(visible));
        }
        return cteColumns;
    }

    Set<Integer> recoveredProjectionTokens() {
        if (recoveredProjections == null) {
            recoveredProjections = Collections.unmodifiableSet(HiveGrammarExtensions.unknownProjectionTokens(visible));
        }
        return recoveredProjections;
    }

    Set<Integer> quotedSelectAliases() {
        if (quotedAliases == null) {
            quotedAliases = Collections.unmodifiableSet(HiveGrammarExtensions.quotedSelectAliases(visible));
        }
        return quotedAliases;
    }

    Set<Integer> overwriteColumnExtensions() {
        if (overwriteColumns == null) {
            overwriteColumns = Collections.unmodifiableSet(HiveCompatibilityTokens.overwriteColumnExtensions(visible));
        }
        return overwriteColumns;
    }

    int firstVisibleAt(int position) {
        int low = 0;
        int high = visible.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (visible.get(middle).getSourceRef().getSpan().getStart() < position) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    boolean between(int start, int end, String symbol) {
        List<Integer> positions = symbols.get(symbol);
        if (positions == null || start >= end) {
            return false;
        }
        int index = Collections.binarySearch(positions, start);
        index = index < 0 ? -index - 1 : index;
        return index < positions.size() && positions.get(index) < end;
    }

    InsertModeSyntax modeBefore(int position) {
        Map.Entry<Integer, InsertModeSyntax> entry = insertModes.lowerEntry(position);
        return entry == null ? null : entry.getValue();
    }
}
