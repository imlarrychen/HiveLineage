package com.panfan.hivelineage.v2.dialect.hive;

import com.panfan.hivelineage.v2.lexical.LexToken;
import com.panfan.hivelineage.v2.lexical.TokenKind;
import com.panfan.hivelineage.v2.syntax.IdentifierPart;
import com.panfan.hivelineage.v2.syntax.QuoteKind;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.Token;
import org.antlr.runtime.TokenSource;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.ParseDriver;

/** Reads declaration identifiers, including the grammar's non-reserved keywords. */
final class HiveIdentifierReader {
    IdentifierPart read(final LexToken token) {
        if (token.getKind() != TokenKind.IDENTIFIER && token.getKind() != TokenKind.KEYWORD) {
            return null;
        }
        String spelling = token.getLexeme();
        if (HiveAstSupport.unquote(spelling).isEmpty() || !identifierToken(token)) {
            return null;
        }
        return new IdentifierPart(spelling, HiveAstSupport.unquote(spelling),
                spelling.startsWith("`") ? QuoteKind.BACKTICK : QuoteKind.NONE, token.getSourceRef());
    }

    private boolean identifierToken(final LexToken token) {
        if (token.getKind() == TokenKind.IDENTIFIER) {
            return true;
        }
        CommonTokenStream stream = new CommonTokenStream(new TokenSource() {
            private boolean consumed;

            @Override
            public Token nextToken() {
                if (consumed) {
                    return new CommonToken(Token.EOF, "");
                }
                consumed = true;
                for (int type = 0; type < HiveParser.tokenNames.length; type++) {
                    if (token.getDialectSymbol().getSymbolName().equals(HiveParser.tokenNames[type])) {
                        return new CommonToken(type, token.getLexeme());
                    }
                }
                return new CommonToken(Token.INVALID_TOKEN_TYPE, token.getLexeme());
            }

            @Override
            public String getSourceName() {
                return token.getSourceRef().getSourceId();
            }
        });
        HiveParser parser = new HiveParser(stream) {
            @Override
            public void displayRecognitionError(String[] tokenNames, RecognitionException error) {
                // The command parser reports one stable diagnostic at its stage boundary.
            }
        };
        parser.setTreeAdaptor(ParseDriver.adaptor);
        try {
            parser.identifier();
            return parser.getNumberOfSyntaxErrors() == 0 && stream.LA(1) == Token.EOF;
        } catch (RecognitionException error) {
            return false;
        }
    }
}
