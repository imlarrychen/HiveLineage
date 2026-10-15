package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.InsertModeSyntax;
import com.panfan.hivelineage.v2.syntax.QuoteKind;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hive.ql.parse.ASTNode;

/** Maps native token positions to expanded source and indexed lexical extensions. */
final class HiveSourceMapper {
    private final LexedUnit unit;
    private final HiveTokenIndex tokens;

    HiveSourceMapper(LexedUnit unit, HiveTokenIndex tokens) {
        this.unit = unit;
        this.tokens = tokens;
    }

    SourceRef ref(ASTNode node) {
        String sourceId = unit.getExpandedUnit().getExpandedSourceId();
        if (node.getTokenStartIndex() < 0 || node.getTokenStopIndex() < node.getTokenStartIndex()) {
            return SourceRef.synthetic(sourceId);
        }
        int startIndex = node.getTokenStartIndex();
        int stopIndex = Math.max(startIndex, node.getTokenStopIndex());
        List<LexToken> tokens = unit.getTokens();
        if (startIndex >= tokens.size()) {
            return SourceRef.synthetic(sourceId);
        }
        int start = tokens.get(startIndex).getSourceRef().getSpan().getStart();
        int end = tokens.get(Math.min(stopIndex, tokens.size() - 1)).getSourceRef().getSpan().getEnd();
        return new SourceRef(sourceId, new SourceSpan(sourceId, start, Math.max(start, end)));
    }

    boolean betweenHas(ASTNode left, ASTNode right, String symbol) {
        int end = ref(left).getSpan().getEnd();
        int start = ref(right).getSpan().getStart();
        return tokens.between(end, start, symbol);
    }

    InsertModeSyntax insertModeBefore(int end) {
        return tokens.modeBefore(end);
    }

    boolean recoveredExpression(ASTNode node) {
        int index = node.getToken() == null ? -1 : node.getToken().getTokenIndex();
        return index >= 0 && index < unit.getTokens().size() && unit.getTokens().get(index).getKind() == TokenKind.UNKNOWN;
    }

    List<IdentifierPart> cteColumns(ASTNode alias) {
        List<IdentifierPart> columns = new ArrayList<IdentifierPart>();
        boolean list = false;
        List<LexToken> visible = tokens.visible();
        for (int index = tokens.firstVisibleAt(ref(alias).getSpan().getEnd()); index < visible.size(); index++) {
            LexToken token = visible.get(index);
            if (!list) {
                if (!"(".equals(token.getLexeme())) {
                    return columns;
                }
                list = true;
            } else if (")".equals(token.getLexeme())) {
                return columns;
            } else if (!",".equals(token.getLexeme())) {
                columns.add(new IdentifierPart(token.getLexeme(), HiveAstSupport.unquote(token.getLexeme()),
                        token.getLexeme().startsWith("`") ? QuoteKind.BACKTICK : QuoteKind.NONE, token.getSourceRef()));
            }
        }
        return columns;
    }

    String source(ASTNode node) {
        SourceSpan range = ref(node).getSpan();
        return unit.getExpandedUnit().getExpandedText().substring(range.getStart(), range.getEnd());
    }

}
