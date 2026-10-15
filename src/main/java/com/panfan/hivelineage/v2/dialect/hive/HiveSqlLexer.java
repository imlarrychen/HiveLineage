package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.common.AnalysisStage;
import com.panfan.hivelineage.v2.common.Diagnostic;
import com.panfan.hivelineage.v2.common.SourceRef;
import com.panfan.hivelineage.v2.common.SourceSpan;
import com.panfan.hivelineage.v2.frontend.ExpandedUnit;
import com.panfan.hivelineage.v2.frontend.StageOutcome;
import com.panfan.hivelineage.v2.lexical.DialectSymbol;
import com.panfan.hivelineage.v2.lexical.LexResult;
import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.LexedUnit;
import com.panfan.hivelineage.v2.lexical.LexicalOptions;
import com.panfan.hivelineage.v2.lexical.SqlLexer;
import com.panfan.hivelineage.v2.lexical.TokenChannel;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.lexical.TokenLocation;
import com.panfan.hivelineage.v2.lexical.Trivia;
import com.panfan.hivelineage.v2.lexical.TriviaKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.parse.GenericHiveLexer;
import org.apache.hadoop.hive.ql.parse.HiveParser;

public final class HiveSqlLexer implements SqlLexer {
    @Override
    public LexResult tokenize(ExpandedUnit unit, LexicalOptions options) {
        Configuration configuration = new Configuration(false);
        configuration.set("hive.support.quoted.identifiers", "column");
        GenericHiveLexer lexer = GenericHiveLexer.of(unit.getExpandedText(), configuration);
        List<LexToken> tokens = new ArrayList<LexToken>();
        List<Trivia> trivia = new ArrayList<Trivia>();
        int covered = 0;
        Token token = lexer.nextToken();
        while (token.getType() != Token.EOF) {
            int start = Math.max(0, ((CommonToken) token).getStartIndex());
            HiveLexicalGaps.append(tokens, unit, covered, start);
            LexToken converted = convert(token, tokens.size(), unit);
            covered = converted.getSourceRef().getSpan().getEnd();
            tokens.add(converted);
            if (token.getChannel() != Token.DEFAULT_CHANNEL && options.isPreserveTrivia()) {
                trivia.add(new Trivia(triviaKind(converted.getLexeme()), converted.getLexeme(), converted.getSourceRef()));
            }
            token = lexer.nextToken();
        }
        HiveLexicalGaps.append(tokens, unit, covered, unit.getExpandedText().length());
        tokens.add(convert(token, tokens.size(), unit));
        LexedUnit lexed = new LexedUnit(unit, tokens, trivia);
        if (!lexer.getErrors().isEmpty()) {
            Diagnostic diagnostic = new Diagnostic(AnalysisStage.LEXICAL, "HIVE_LEXICAL_ERROR",
                    "Hive lexer reported " + lexer.getErrors().size() + " invalid lexical input(s)",
                    unit.getOriginalUnit().getSourceRef());
            return new LexResult(lexed, Collections.singletonList(diagnostic), StageOutcome.PARTIAL);
        }
        return new LexResult(lexed, Collections.<Diagnostic>emptyList(), StageOutcome.SUCCESS);
    }

    private LexToken convert(Token token, int index, ExpandedUnit unit) {
        int type = token.getType();
        String text = type == Token.EOF ? "" : token.getText();
        int length = unit.getExpandedText().length();
        int start = type == Token.EOF ? length : Math.max(0, ((CommonToken) token).getStartIndex());
        int end = type == Token.EOF ? length : Math.min(length, ((CommonToken) token).getStopIndex() + 1);
        String symbol = type == Token.EOF ? "EOF" : HiveParser.tokenNames[type];
        SourceRef source = new SourceRef(unit.getExpandedSourceId(), new SourceSpan(unit.getExpandedSourceId(), start, end));
        TokenKind kind = kind(symbol);
        if (kind == TokenKind.IDENTIFIER) {
            // The native lexer decodes backtick identifiers; retain their declaration spelling.
            text = unit.getExpandedText().substring(start, end);
        }
        return new LexToken(index, kind, text, decoded(kind, text),
                token.getChannel() == Token.DEFAULT_CHANNEL ? TokenChannel.DEFAULT : TokenChannel.HIDDEN,
                new TokenLocation(source, new DialectSymbol("hive", symbol)));
    }

    private TokenKind kind(String symbol) {
        if ("EOF".equals(symbol)) {
            return TokenKind.END_OF_INPUT;
        }
        if ("QUESTION".equals(symbol)) {
            return TokenKind.UNKNOWN;
        }
        if ("Identifier".equals(symbol)) {
            return TokenKind.IDENTIFIER;
        }
        if (symbol.contains("StringLiteral")) {
            return TokenKind.STRING_LITERAL;
        }
        if (symbol.contains("Number") || symbol.contains("IntegralLiteral")) {
            return TokenKind.NUMBER_LITERAL;
        }
        if (symbol.startsWith("KW_")) {
            return TokenKind.KEYWORD;
        }
        return TokenKind.PUNCTUATION;
    }

    private String decoded(TokenKind kind, String text) {
        if (kind == TokenKind.IDENTIFIER || kind == TokenKind.STRING_LITERAL) {
            return HiveAstSupport.unquote(text);
        }
        return null;
    }

    private TriviaKind triviaKind(String text) {
        if (text.startsWith("--")) {
            return TriviaKind.LINE_COMMENT;
        }
        return text.startsWith("/*") ? TriviaKind.BLOCK_COMMENT : TriviaKind.WHITESPACE;
    }
}
