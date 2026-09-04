package com.panfan.hivelineage.core;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BacktickColumnReferenceExtractor {
    private static final Pattern SIMPLE_BACKTICK_IDENTIFIER = Pattern.compile("`([^`]+)`");

    private BacktickColumnReferenceExtractor() {
    }

    static String maskAndCollect(
        String expression,
        Set<String> columns,
        String defaultSourceTable,
        QualifiedOwnerResolver ownerResolver
    ) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        StringBuilder masked = new StringBuilder(expression);
        Matcher matcher = SIMPLE_BACKTICK_IDENTIFIER.matcher(expression);
        while (matcher.find()) {
            collectBacktickColumn(expression, matcher, columns, defaultSourceTable, ownerResolver);
            for (int i = matcher.start(); i < matcher.end(); i++) {
                masked.setCharAt(i, ' ');
            }
        }
        return masked.toString();
    }

    private static void collectBacktickColumn(
        String expression,
        Matcher matcher,
        Set<String> columns,
        String defaultSourceTable,
        QualifiedOwnerResolver ownerResolver
    ) {
        String columnName = matcher.group(1).toLowerCase(Locale.ROOT);
        String owner = ownerBeforeBacktickIdentifier(expression, matcher.start());
        if (owner != null) {
            String resolvedOwner = ownerResolver.resolve(owner);
            if (resolvedOwner != null) {
                columns.add(resolvedOwner + "." + columnName);
            }
        } else if (defaultSourceTable != null) {
            columns.add(defaultSourceTable + "." + columnName);
        }
    }

    private static String ownerBeforeBacktickIdentifier(String expression, int backtickStart) {
        int dot = backtickStart - 1;
        while (dot >= 0 && Character.isWhitespace(expression.charAt(dot))) {
            dot--;
        }
        if (dot < 0 || expression.charAt(dot) != '.') {
            return null;
        }
        int ownerEnd = dot;
        int ownerStart = ownerEnd - 1;
        while (ownerStart >= 0 && HiveSqlTextUtils.isIdentifierCharacter(expression.charAt(ownerStart))) {
            ownerStart--;
        }
        return ownerStart + 1 >= ownerEnd ? null : expression.substring(ownerStart + 1, ownerEnd);
    }
}
