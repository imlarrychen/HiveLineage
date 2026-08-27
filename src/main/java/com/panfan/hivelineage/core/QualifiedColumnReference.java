package com.panfan.hivelineage.core;

import java.util.Locale;
import java.util.regex.Pattern;

final class QualifiedColumnReference {

    private static final String IDENTIFIER = "[a-z_][a-z0-9_]*";
    private static final Pattern COLUMN_REFERENCE = Pattern.compile(
            IDENTIFIER + "(?:\\." + IDENTIFIER + "){0,2}", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAR_REFERENCE = Pattern.compile(
            "(?:" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")?\\.)?\\*",
            Pattern.CASE_INSENSITIVE);

    private final String owner;
    private final String column;

    private QualifiedColumnReference(String owner, String column) {
        this.owner = owner;
        this.column = column;
    }

    static QualifiedColumnReference parse(String text) {
        if (text == null) {
            return null;
        }

        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (!COLUMN_REFERENCE.matcher(normalized).matches()
                && !STAR_REFERENCE.matcher(normalized).matches()) {
            return null;
        }

        int separator = normalized.lastIndexOf('.');
        if (separator < 0) {
            return new QualifiedColumnReference(null, normalized);
        }
        return new QualifiedColumnReference(
                normalized.substring(0, separator),
                normalized.substring(separator + 1));
    }

    String getOwner() {
        return owner;
    }

    String getColumn() {
        return column;
    }

    boolean isStar() {
        return "*".equals(column);
    }
}
