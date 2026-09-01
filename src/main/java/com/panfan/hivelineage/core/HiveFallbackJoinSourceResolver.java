package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
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

    private static final class RelationParse {
        private final FallbackJoinSource source;
        private final int lastTokenIndex;

        private RelationParse(FallbackJoinSource source, int lastTokenIndex) {
            this.source = source;
            this.lastTokenIndex = lastTokenIndex;
        }
    }

    private static final class ParseContext {
        private final List<Token> tokens;
        private final Set<String> temporaryTables;
        private final List<FallbackJoinSource> output;

        private ParseContext(List<Token> tokens, Set<String> temporaryTables, List<FallbackJoinSource> output) {
            this.tokens = tokens;
            this.temporaryTables = temporaryTables;
            this.output = output;
        }
    }

    List<FallbackJoinSource> resolve(String sql, Set<String> temporaryTables) {
        if (sql == null) {
            throw new IllegalArgumentException("sql must not be null");
        }
        List<Token> tokens = tokenize(sql);
        List<FallbackJoinSource> sources = new ArrayList<>();
        ParseContext context = new ParseContext(tokens, normalizeTemporaryNames(temporaryTables), sources);
        parseQueryBlock(context, 0, tokens.size(), "q0", Collections.<String>emptySet());
        Collections.sort(sources, new Comparator<FallbackJoinSource>() {
            @Override
            public int compare(FallbackJoinSource left, FallbackJoinSource right) {
                return Integer.compare(left.getStartOffset(), right.getStartOffset());
            }
        });
        return Collections.unmodifiableList(sources);
    }

    private void parseQueryBlock(
        ParseContext context,
        int start,
        int end,
        String path,
        Set<String> inheritedCtes
    ) {
        List<Token> tokens = context.tokens;
        Set<String> visibleCtes = new LinkedHashSet<>(inheritedCtes);
        int bodyStart = parseCteDeclarations(context, start, end, path, visibleCtes);
        boolean seenSelect = false;
        boolean inFromClause = false;
        boolean expectRelation = false;
        String currentPath = path;
        int unionBranchIndex = 0;
        boolean nextQueryStartsUnionBranch = false;

        int index = bodyStart;
        while (index < end) {
            Token token = tokens.get(index);
            if (token.type == TokenType.LPAREN) {
                int close = matchingParen(tokens, index, end);
                if (expectRelation) {
                    RelationParse relation = parseRelation(context, index, end, currentPath, visibleCtes);
                    context.output.add(relation.source);
                    index = relation.lastTokenIndex + 1;
                    expectRelation = false;
                    continue;
                }
                if (containsQuery(tokens, index + 1, close)) {
                    if (nextQueryStartsUnionBranch) {
                        unionBranchIndex++;
                        currentPath = unionBranchPath(path, unionBranchIndex);
                        nextQueryStartsUnionBranch = false;
                    }
                    parseQueryBlock(context, index + 1, close, childPath(currentPath, index), visibleCtes);
                }
                index = close + 1;
                continue;
            }
            if (token.type == TokenType.RPAREN) {
                throw new IllegalArgumentException("unmatched closing parenthesis in query");
            }
            if (expectRelation && !isRelationIdentifier(token)) {
                throw new IllegalArgumentException("relation identifier is required");
            }
            if (isKeyword(token, "union")) {
                seenSelect = false;
                inFromClause = false;
                expectRelation = false;
                nextQueryStartsUnionBranch = true;
                index++;
                continue;
            }
            if (isKeyword(token, "select")) {
                if (nextQueryStartsUnionBranch) {
                    unionBranchIndex++;
                    currentPath = unionBranchPath(path, unionBranchIndex);
                    nextQueryStartsUnionBranch = false;
                }
                seenSelect = true;
                index++;
                continue;
            }
            if (seenSelect && !inFromClause && isKeyword(token, "from")) {
                inFromClause = true;
                expectRelation = true;
                index++;
                continue;
            }
            if (inFromClause && isClauseBoundary(token)) {
                inFromClause = false;
                expectRelation = false;
                index++;
                continue;
            }
            if (inFromClause && isKeyword(token, "join")) {
                expectRelation = true;
                index++;
                continue;
            }
            if (inFromClause && (isKeyword(token, "on") || isKeyword(token, "using"))) {
                expectRelation = false;
                index++;
                continue;
            }
            if (inFromClause && token.type == TokenType.COMMA) {
                expectRelation = true;
                index++;
                continue;
            }
            if (expectRelation) {
                RelationParse relation = parseRelation(context, index, end, currentPath, visibleCtes);
                context.output.add(relation.source);
                index = relation.lastTokenIndex + 1;
                expectRelation = false;
                continue;
            }
            index++;
        }
        if (expectRelation) {
            throw new IllegalArgumentException("missing relation in FROM or JOIN clause");
        }
    }

    private int parseCteDeclarations(
        ParseContext context,
        int start,
        int end,
        String path,
        Set<String> visibleCtes
    ) {
        List<Token> tokens = context.tokens;
        if (start >= end || !isKeyword(tokens.get(start), "with")) {
            return start;
        }
        int index = start + 1;
        while (true) {
            if (index >= end || !isIdentifier(tokens.get(index))) {
                throw new IllegalArgumentException("CTE name is required after WITH");
            }
            String cteName = tokens.get(index).text;
            index++;
            if (index < end && tokens.get(index).type == TokenType.LPAREN) {
                index = consumeCteColumnAliases(tokens, index, end);
            }
            if (index >= end || !isKeyword(tokens.get(index), "as")) {
                throw new IllegalArgumentException("AS is required after CTE name");
            }
            index++;
            if (index >= end || tokens.get(index).type != TokenType.LPAREN) {
                throw new IllegalArgumentException("CTE query must be parenthesized");
            }
            int open = index;
            int close = matchingParen(tokens, open, end);
            if (!containsQuery(tokens, open + 1, close)) {
                throw new IllegalArgumentException("CTE body must contain a query");
            }
            parseQueryBlock(context, open + 1, close, childPath(path, open), visibleCtes);
            visibleCtes.add(cteName);
            index = close + 1;
            if (index >= end || tokens.get(index).type != TokenType.COMMA) {
                return index;
            }
            index++;
        }
    }

    private int consumeCteColumnAliases(List<Token> tokens, int open, int end) {
        int index = open + 1;
        if (index >= end || !isIdentifier(tokens.get(index))) {
            throw new IllegalArgumentException("CTE column alias is required");
        }
        while (true) {
            index++;
            if (index >= end) {
                throw new IllegalArgumentException("CTE column alias list is not closed");
            }
            Token token = tokens.get(index);
            if (token.type == TokenType.RPAREN) {
                return index + 1;
            }
            if (token.type != TokenType.COMMA) {
                throw new IllegalArgumentException(
                    "comma or closing parenthesis is required in CTE column alias list"
                );
            }
            index++;
            if (index >= end || !isIdentifier(tokens.get(index))) {
                throw new IllegalArgumentException("CTE column alias is required after comma");
            }
        }
    }

    private RelationParse parseRelation(
        ParseContext context,
        int index,
        int end,
        String path,
        Set<String> visibleCtes
    ) {
        List<Token> tokens = context.tokens;
        Token first = tokens.get(index);
        if (first.type == TokenType.LPAREN) {
            int close = matchingParen(tokens, index, end);
            if (!containsQuery(tokens, index + 1, close)) {
                throw new IllegalArgumentException("parenthesized relation must contain a query");
            }
            parseQueryBlock(context, index + 1, close, childPath(path, index), visibleCtes);
            int aliasIndex = close + 1;
            if (aliasIndex < end && isKeyword(tokens.get(aliasIndex), "as")) {
                aliasIndex++;
            }
            if (aliasIndex >= end || !isRelationIdentifier(tokens.get(aliasIndex))) {
                throw new IllegalArgumentException("subquery relation alias is required");
            }
            Token alias = tokens.get(aliasIndex);
            return new RelationParse(new FallbackJoinSource(alias.text, path, alias.offset,
                FallbackJoinSource.Kind.SUBQUERY), aliasIndex);
        }
        if (!isRelationIdentifier(first)) {
            throw new IllegalArgumentException("relation identifier is required");
        }

        String name = first.text;
        int lastIndex = index;
        boolean qualified = false;
        if (index + 1 < end && tokens.get(index + 1).type == TokenType.DOT) {
            if (index + 2 >= end || !isIdentifier(tokens.get(index + 2))) {
                throw new IllegalArgumentException("qualified relation is incomplete");
            }
            name = name + "." + tokens.get(index + 2).text;
            lastIndex = index + 2;
            qualified = true;
            if (lastIndex + 1 < end && tokens.get(lastIndex + 1).type == TokenType.DOT) {
                throw new IllegalArgumentException("relation must have one or two parts");
            }
        }

        FallbackJoinSource.Kind kind = classifyRelation(name, qualified, visibleCtes, context.temporaryTables);
        return new RelationParse(new FallbackJoinSource(name, path, first.offset, kind),
            lastIndex);
    }

    private FallbackJoinSource.Kind classifyRelation(
        String name,
        boolean qualified,
        Set<String> visibleCtes,
        Set<String> temporaryTables
    ) {
        if (qualified) {
            return FallbackJoinSource.Kind.PHYSICAL;
        }
        if (visibleCtes.contains(name)) {
            return FallbackJoinSource.Kind.CTE;
        }
        if (temporaryTables.contains(name)) {
            return FallbackJoinSource.Kind.TEMPORARY;
        }
        return FallbackJoinSource.Kind.PHYSICAL;
    }

    private Set<String> normalizeTemporaryNames(Set<String> temporaryTables) {
        if (temporaryTables == null || temporaryTables.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : temporaryTables) {
            if (name == null) {
                continue;
            }
            String value = normalizeTemporaryName(name.trim());
            if (!value.isEmpty()) {
                normalized.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    private String normalizeTemporaryName(String value) {
        if (value.length() >= 2 && value.charAt(0) == '`'
            && value.charAt(value.length() - 1) == '`') {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }

    private int skipLineComment(String sql, int index) {
        int cursor = index;
        while (cursor < sql.length() && sql.charAt(cursor) != '\n' && sql.charAt(cursor) != '\r') {
            cursor++;
        }
        return cursor;
    }

    private int skipBlockComment(String sql, int index) {
        int cursor = index;
        while (cursor + 1 < sql.length()) {
            if (sql.charAt(cursor) == '*' && sql.charAt(cursor + 1) == '/') {
                return cursor + 2;
            }
            cursor++;
        }
        throw new IllegalArgumentException("unclosed block comment");
    }

    private int matchingParen(List<Token> tokens, int open, int end) {
        int depth = 0;
        for (int index = open; index < end; index++) {
            TokenType type = tokens.get(index).type;
            if (type == TokenType.LPAREN) {
                depth++;
            } else if (type == TokenType.RPAREN) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new IllegalArgumentException("unclosed parenthesis in query");
    }

    private boolean containsQuery(List<Token> tokens, int start, int end) {
        for (int index = start; index < end; index++) {
            if (isKeyword(tokens.get(index), "select") || isKeyword(tokens.get(index), "with")) {
                return true;
            }
        }
        return false;
    }

    private String childPath(String path, int tokenIndex) {
        return path + ".q" + tokenIndex;
    }

    private String unionBranchPath(String path, int branchIndex) {
        return path + ".u" + branchIndex;
    }

    private boolean isClauseBoundary(Token token) {
        return isFilteringOrGroupingBoundary(token)
            || isOrderingOrLimitBoundary(token)
            || isHiveSpecificBoundary(token);
    }

    private boolean isFilteringOrGroupingBoundary(Token token) {
        return isKeyword(token, "where") || isKeyword(token, "group")
            || isKeyword(token, "having");
    }

    private boolean isOrderingOrLimitBoundary(Token token) {
        return isKeyword(token, "order") || isKeyword(token, "limit")
            || isKeyword(token, "sort");
    }

    private boolean isHiveSpecificBoundary(Token token) {
        return isKeyword(token, "cluster") || isKeyword(token, "distribute")
            || isKeyword(token, "union") || isKeyword(token, "qualify")
            || isKeyword(token, "window");
    }

    private boolean isKeyword(Token token, String keyword) {
        return token.type == TokenType.WORD && keyword.equals(token.text);
    }

    private boolean isIdentifier(Token token) {
        return token.type == TokenType.WORD || token.type == TokenType.QUOTED_IDENTIFIER;
    }

    private boolean isRelationIdentifier(Token token) {
        if (token.type == TokenType.QUOTED_IDENTIFIER) {
            return true;
        }
        return token.type == TokenType.WORD
            && !isRelationSyntaxKeyword(token)
            && !isClauseBoundary(token);
    }

    private boolean isRelationSyntaxKeyword(Token token) {
        return isQuerySyntaxKeyword(token) || isJoinSyntaxKeyword(token);
    }

    private boolean isQuerySyntaxKeyword(Token token) {
        return isKeyword(token, "select") || isKeyword(token, "from")
            || isKeyword(token, "as");
    }

    private boolean isJoinSyntaxKeyword(Token token) {
        return isKeyword(token, "join") || isKeyword(token, "on")
            || isKeyword(token, "using");
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
        throw new IllegalArgumentException("unclosed string literal");
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
                if (text.length() == 0) {
                    throw new IllegalArgumentException("quoted identifier must not be empty");
                }
                tokens.add(new Token(TokenType.QUOTED_IDENTIFIER,
                    text.toString().toLowerCase(Locale.ROOT), start));
                return index + 1;
            }
            text.append(current);
            index++;
        }
        throw new IllegalArgumentException("unclosed quoted identifier");
    }

    private int readWord(String sql, int start, List<Token> tokens) {
        int index = start + 1;
        while (index < sql.length() && isWordCharacter(sql.charAt(index))) {
            index++;
        }
        tokens.add(new Token(TokenType.WORD,
            sql.substring(start, index).toLowerCase(Locale.ROOT), start));
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
