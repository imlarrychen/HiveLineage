package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class HiveSqlTextUtils {
    private HiveSqlTextUtils() {
    }

    static int findMatchingParen(String text, int openIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }
    static int topLevelKeywordIndex(String sql, String keyword, int start) {
        int depth = 0;
        char quote = 0;
        for (int i = Math.max(0, start); i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && matchesKeywordAt(sql, i, keyword)) {
                return i;
            }
        }
        return -1;
    }
    static int lastTopLevelKeywordIndex(String statement, String keyword) {
        int found = -1;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < statement.length(); i++) {
            char ch = statement.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && matchesKeywordAt(statement, i, keyword)) {
                found = i;
            }
        }
        return found;
    }
    static boolean matchesKeywordAt(String statement, int index, String keyword) {
        int end = index + keyword.length();
        if (index < 0 || end > statement.length()) {
            return false;
        }
        if (!statement.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        return (index == 0 || !isIdentifierCharacter(statement.charAt(index - 1)))
            && (end == statement.length() || !isIdentifierCharacter(statement.charAt(end)));
    }
    static int skipWhitespace(String statement, int position) {
        while (position < statement.length() && Character.isWhitespace(statement.charAt(position))) {
            position++;
        }
        return position;
    }
    static int readIdentifier(String statement, int position) {
        while (position < statement.length() && isIdentifierCharacter(statement.charAt(position))) {
            position++;
        }
        return position;
    }
    static boolean isIdentifierCharacter(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.';
    }
    static String normalizeIdentifier(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
    }
    static String tableNameOfColumn(String qualifiedColumnName) {
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex < 0 ? null : qualifiedColumnName.substring(0, dotIndex);
    }
    static String columnNameOf(String qualifiedColumnName) {
        int dotIndex = qualifiedColumnName.lastIndexOf('.');
        return dotIndex < 0 ? null : qualifiedColumnName.substring(dotIndex + 1);
    }
    static String joinWithComma(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(value);
        }
        return builder.toString();
    }
    static List<String> splitSelectItems(String selectList) {
        List<String> items = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < selectList.length(); i++) {
            char ch = selectList.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                items.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (current.length() > 0) {
            items.add(current.toString().trim());
        }
        return items;
    }
    static String stripAlias(String selectItem) {
        int asIndex = topLevelAsIndex(selectItem);
        if (asIndex < 0) {
            return selectItem.trim();
        }
        return selectItem.substring(0, asIndex).trim();
    }
    static String extractAlias(String selectItem) {
        int asIndex = topLevelAsIndex(selectItem);
        if (asIndex < 0) {
            return null;
        }
        return selectItem.substring(asIndex + 4).trim();
    }
    static int topLevelAsIndex(String selectItem) {
        String lower = selectItem.toLowerCase(Locale.ROOT);
        int depth = 0;
        int found = -1;
        for (int i = 0; i <= lower.length() - 4; i++) {
            char ch = lower.charAt(i);
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth == 0 && lower.startsWith(" as ", i)) {
                found = i;
            }
        }
        return found;
    }
    static String removeStringLiterals(String expression) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (inSingleQuote) {
                if (ch == '\'' && i + 1 < expression.length() && expression.charAt(i + 1) == '\'') {
                    builder.append(' ');
                    i++;
                    builder.append(' ');
                    continue;
                }
                if (ch == '\'') {
                    inSingleQuote = false;
                }
                builder.append(' ');
                continue;
            }
            if (inDoubleQuote) {
                if (ch == '"' && i + 1 < expression.length() && expression.charAt(i + 1) == '"') {
                    builder.append(' ');
                    i++;
                    builder.append(' ');
                    continue;
                }
                if (ch == '"') {
                    inDoubleQuote = false;
                }
                builder.append(' ');
                continue;
            }
            if (ch == '\'') {
                inSingleQuote = true;
                builder.append(' ');
            } else if (ch == '"') {
                inDoubleQuote = true;
                builder.append(' ');
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }
    static boolean isAliasKeyword(String token) {
        return Arrays.asList(
            "on", "where", "left", "right", "inner", "outer", "full", "join", "union",
            "group", "order", "having", "limit", "cross", "semi"
        ).contains(token.toLowerCase(Locale.ROOT));
    }
}
