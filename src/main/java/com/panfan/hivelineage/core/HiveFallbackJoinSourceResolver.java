package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class HiveFallbackJoinSourceResolver {
    private enum TokenType {
        WORD,
        QUOTED_IDENTIFIER,
        DOT,
        COMMA,
        LPAREN,
        RPAREN
    }

    private static final class Token {
        private final TokenType type;
        private final String text;
        private final int offset;

        private Token(TokenType type, String text, int offset) {
            this.type = type;
            this.text = text;
            this.offset = offset;
        }
    }

    List<FallbackJoinSource> resolve(String sql, Set<String> temporaryTables) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        List<Token> tokens = tokenize(sql);
        List<FallbackJoinSource> sources = new ArrayList<>();
        parseQueryBlock(tokens, sources);
        return Collections.unmodifiableList(sources);
    }

    private void parseQueryBlock(List<Token> tokens, List<FallbackJoinSource> sources) {
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (!isKeyword(token, "from") && !isKeyword(token, "join")) {
                continue;
            }
            int relationIndex = index + 1;
            if (relationIndex >= tokens.size() || !isIdentifier(tokens.get(relationIndex))) {
                continue;
            }
            Token first = tokens.get(relationIndex);
            String name = first.text;
            if (relationIndex + 2 < tokens.size()
                && tokens.get(relationIndex + 1).type == TokenType.DOT
                && isIdentifier(tokens.get(relationIndex + 2))) {
                name = name + "." + tokens.get(relationIndex + 2).text;
            }
            sources.add(new FallbackJoinSource(
                name,
                "q0",
                first.offset,
                FallbackJoinSource.Kind.PHYSICAL
            ));
        }
    }

    private boolean isKeyword(Token token, String keyword) {
        return token.type == TokenType.WORD && keyword.equals(token.text);
    }

    private boolean isIdentifier(Token token) {
        return token.type == TokenType.WORD || token.type == TokenType.QUOTED_IDENTIFIER;
    }

    private List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '-' && hasNext(sql, index, '-')) {
                index = skipLineComment(sql, index + 2);
            } else if (current == '/' && hasNext(sql, index, '*')) {
                index = skipBlockComment(sql, index + 2);
            } else if (current == '\'' || current == '"') {
                index = skipString(sql, index, current);
            } else if (current == '`') {
                index = readQuotedIdentifier(sql, index, tokens);
            } else if (isWordCharacter(current)) {
                index = readWord(sql, index, tokens);
            } else {
                TokenType type = punctuationType(current);
                if (type != null) {
                    tokens.add(new Token(type, String.valueOf(current), index));
                }
                index++;
            }
        }
        return tokens;
    }

    private boolean hasNext(String sql, int index, char expected) {
        return index + 1 < sql.length() && sql.charAt(index + 1) == expected;
    }

    private int skipLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    private int skipBlockComment(String sql, int index) {
        while (index + 1 < sql.length()) {
            if (sql.charAt(index) == '*' && sql.charAt(index + 1) == '/') {
                return index + 2;
            }
            index++;
        }
        return sql.length();
    }

    private int skipString(String sql, int start, char quote) {
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\\') {
                index = Math.min(index + 2, sql.length());
                continue;
            }
            if (current == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }

    private int readQuotedIdentifier(String sql, int start, List<Token> tokens) {
        StringBuilder text = new StringBuilder();
        int index = start + 1;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '`') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '`') {
                    text.append('`');
                    index += 2;
                    continue;
                }
                tokens.add(new Token(
                    TokenType.QUOTED_IDENTIFIER,
                    text.toString().toLowerCase(Locale.ROOT),
                    start
                ));
                return index + 1;
            }
            text.append(current);
            index++;
        }
        return sql.length();
    }

    private int readWord(String sql, int start, List<Token> tokens) {
        int index = start + 1;
        while (index < sql.length() && isWordCharacter(sql.charAt(index))) {
            index++;
        }
        tokens.add(new Token(
            TokenType.WORD,
            sql.substring(start, index).toLowerCase(Locale.ROOT),
            start
        ));
        return index;
    }

    private boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private TokenType punctuationType(char value) {
        switch (value) {
            case '.':
                return TokenType.DOT;
            case ',':
                return TokenType.COMMA;
            case '(':
                return TokenType.LPAREN;
            case ')':
                return TokenType.RPAREN;
            default:
                return null;
        }
    }
}
