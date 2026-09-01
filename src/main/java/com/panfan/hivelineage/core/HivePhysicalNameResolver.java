package com.panfan.hivelineage.core;

import com.panfan.hivelineage.metadata.TableIdentifier;
import java.util.Locale;

final class HivePhysicalNameResolver {
    private static final String DOT_REGEX = "\\.";

    String normalizeDatabase(String database) {
        if (isBlank(database)) {
            return null;
        }
        String[] parts = normalizeParts(database);
        if (parts.length != 1) {
            throw new IllegalArgumentException("Expected database identifier, got " + database);
        }
        return parts[0].isEmpty() ? null : parts[0];
    }

    String qualifyTable(String tableName, String statementDatabase) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new DatabaseResolutionException(String.valueOf(tableName));
        }
        String[] parts = normalizeParts(tableName);
        if (parts.length == 1 && parts[0].isEmpty()) {
            throw new DatabaseResolutionException(tableName);
        }
        if (parts.length == 2) {
            if (parts[0].isEmpty() || parts[1].isEmpty()) {
                throw new IllegalArgumentException("Expected non-empty identifier parts, got " + tableName);
            }
            return parts[0] + "." + parts[1];
        }
        if (parts.length != 1) {
            throw new IllegalArgumentException("Expected table or database.table, got " + tableName);
        }
        String database = normalizeDatabase(statementDatabase);
        if (database == null) {
            throw new DatabaseResolutionException(parts[0]);
        }
        return database + "." + parts[0];
    }

    TableIdentifier toTableIdentifier(String tableName, String clusterName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new DatabaseResolutionException(String.valueOf(tableName));
        }
        String[] parts = normalizeParts(tableName);
        if (parts.length == 1) {
            throw new DatabaseResolutionException(parts[0]);
        }
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("Expected database.table, got " + tableName);
        }
        return TableIdentifier.of(clusterName, parts[0], parts[1]);
    }

    private String[] normalizeParts(String identifier) {
        String[] rawParts = identifier.trim().split(DOT_REGEX, -1);
        String[] normalizedParts = new String[rawParts.length];
        for (int i = 0; i < rawParts.length; i++) {
            normalizedParts[i] = normalizePart(rawParts[i]);
        }
        return normalizedParts;
    }

    private String normalizePart(String part) {
        String trimmed = part.trim();
        boolean startsWithBacktick = trimmed.startsWith("`");
        boolean endsWithBacktick = trimmed.endsWith("`");
        if (startsWithBacktick != endsWithBacktick) {
            throw new IllegalArgumentException("Malformed quoted identifier part: " + part);
        }
        if (startsWithBacktick) {
            if (trimmed.length() < 2
                    || trimmed.substring(1, trimmed.length() - 1).contains("`")) {
                throw new IllegalArgumentException("Malformed quoted identifier part: " + part);
            }
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        } else if (trimmed.contains("`")) {
            throw new IllegalArgumentException("Malformed quoted identifier part: " + part);
        }
        if (trimmed.chars().anyMatch(HivePhysicalNameResolver::isIdentifierWhitespace)) {
            throw new IllegalArgumentException("Whitespace is not allowed in identifier part: " + part);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    static boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isIdentifierWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIdentifierWhitespace(int character) {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }
}
