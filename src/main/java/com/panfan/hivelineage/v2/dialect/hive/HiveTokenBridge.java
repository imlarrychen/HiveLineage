package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import java.util.HashMap;
import java.util.Map;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.antlr.runtime.TokenSource;
import org.apache.hadoop.hive.ql.parse.HiveParser;

/** A fresh cursor over immutable tokens; never calls a Lexer or reads SQL for tokenization. */
final class HiveTokenBridge implements TokenSource {
    private static final Map<String, Integer> SYMBOLS = symbols();
    private final LexedUnit unit;
    private int cursor;
    private final java.util.Set<Integer> overwriteExtensions;
    private final java.util.Set<Integer> hiddenColumns;
    private final java.util.Set<Integer> recoveredExpressions;
    private final java.util.Set<Integer> quotedAliases;
    private int previousRecoveredEnd = -1;

    HiveTokenBridge(LexedUnit unit) {
        this(unit, false);
    }

    HiveTokenBridge(LexedUnit unit, boolean recovery) {
        this(unit, recovery, new HiveTokenIndex(unit));
    }

    HiveTokenBridge(LexedUnit unit, boolean recovery, HiveTokenIndex index) {
        this.unit = unit;
        this.hiddenColumns = index.cteColumnTokens();
        this.recoveredExpressions = recovery ? index.recoveredProjectionTokens()
                : java.util.Collections.<Integer>emptySet();
        this.overwriteExtensions = index.overwriteColumnExtensions();
        this.quotedAliases = index.quotedSelectAliases();
    }

    @Override
    public Token nextToken() {
        if (cursor >= unit.getTokens().size()) {
            return new CommonToken(Token.EOF, "");
        }
        LexToken token = unit.getTokens().get(cursor++);
        String symbol = token.getDialectSymbol().getSymbolName();
        Integer type = "EOF".equals(symbol) ? Integer.valueOf(Token.EOF) : SYMBOLS.get(symbol);
        boolean unknown = token.getKind() == TokenKind.UNKNOWN;
        if (unknown && recoveredExpressions.contains(token.getIndex())) {
            type = HiveParser.Number;
        } else if (unknown && token.getChannel() == TokenChannel.HIDDEN) {
            type = HiveParser.Identifier;
        }
        if (type == null) {
            throw new IllegalArgumentException("Token symbol is not in Hive 4.0.1 vocabulary: " + symbol);
        }
        int compatibleType = overwriteExtensions.contains(token.getIndex()) ? HiveParser.KW_INTO : type;
        if (quotedAliases.contains(token.getIndex())) {
            compatibleType = HiveParser.Identifier;
        }
        CommonToken nativeToken = new CommonToken(compatibleType,
                recoveredExpressions.contains(token.getIndex()) ? "0" : token.getLexeme());
        boolean continuation = recoveredExpressions.contains(token.getIndex())
                && previousRecoveredEnd == token.getSourceRef().getSpan().getStart();
        previousRecoveredEnd = recoveredExpressions.contains(token.getIndex()) ? token.getSourceRef().getSpan().getEnd() : -1;
        nativeToken.setChannel(token.getChannel() == TokenChannel.DEFAULT && !hiddenColumns.contains(token.getIndex()) && !continuation
                ? Token.DEFAULT_CHANNEL : Token.HIDDEN_CHANNEL);
        nativeToken.setStartIndex(token.getSourceRef().getSpan().getStart());
        nativeToken.setStopIndex(token.getSourceRef().getSpan().getEnd() - 1);
        nativeToken.setTokenIndex(token.getIndex());
        nativeToken.setLine(1);
        nativeToken.setCharPositionInLine(token.getSourceRef().getSpan().getStart());
        return nativeToken;
    }

    @Override
    public String getSourceName() {
        return unit.getExpandedUnit().getExpandedSourceId();
    }

    private static Map<String, Integer> symbols() {
        Map<String, Integer> symbols = new HashMap<String, Integer>();
        for (int index = 0; index < HiveParser.tokenNames.length; index++) {
            symbols.put(HiveParser.tokenNames[index], index);
        }
        return java.util.Collections.unmodifiableMap(symbols);
    }
}
