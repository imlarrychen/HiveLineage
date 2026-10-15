package com.panfan.hivelineage.v2.compat;

/**
 * Renders the legacy comment-free normalized SQL without changing frontend source evidence.
 */
final class LegacySqlPresentation {
    private LegacySqlPresentation() {
    }

    static String normalized(String sql) {
        StringBuilder result = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = 0; index < sql.length(); index++) {
            char value = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : 0;
            if (lineComment) {
                if (value == '\n' || value == '\r') {
                    lineComment = false;
                    result.append(value);
                }
            } else if (blockComment) {
                if (value == '*' && next == '/') {
                    blockComment = false;
                    index++;
                } else if (value == '\n' || value == '\r') {
                    result.append(value);
                }
            } else if (quote != 0) {
                result.append(value);
                if (value == '\\' && next != 0) {
                    result.append(next);
                    index++;
                } else if (value == quote && next == quote) {
                    result.append(next);
                    index++;
                } else if (value == quote) {
                    quote = 0;
                }
            } else if (value == '-' && next == '-') {
                lineComment = true;
                index++;
            } else if (value == '/' && next == '*') {
                result.append(' ');
                blockComment = true;
                index++;
            } else {
                result.append(value);
                if (value == '\'' || value == '"' || value == '`') {
                    quote = value;
                }
            }
        }
        return result.toString().trim();
    }
}
