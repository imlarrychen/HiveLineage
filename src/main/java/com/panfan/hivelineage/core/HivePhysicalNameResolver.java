package com.panfan.hivelineage.core;

import java.util.Locale;

final class HivePhysicalNameResolver {
    String normalizeDatabase(String database) {
        if (database == null) {
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

    private String[] normalizeParts(String identifier) {
        String[] rawParts = identifier.trim().split("\\.", -1);
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
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Whitespace is not allowed in identifier part: " + part);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
