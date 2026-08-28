package com.panfan.hivelineage.core;

import java.util.Locale;

final class HivePhysicalNameResolver {
    String normalizeDatabase(String database) {
        String normalized = normalizeParts(database);
        return normalized == null || normalized.isEmpty() ? null : normalized;
    }

    String qualifyTable(String tableName, String statementDatabase) {
        String normalizedTable = normalizeParts(tableName);
        if (normalizedTable == null || normalizedTable.isEmpty()) {
            throw new DatabaseResolutionException(String.valueOf(tableName));
        }
        String[] parts = normalizedTable.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Expected non-empty identifier parts, got " + tableName);
            }
        }
        if (parts.length == 2) {
            return normalizedTable;
        }
        if (parts.length != 1) {
            throw new IllegalArgumentException("Expected table or database.table, got " + tableName);
        }
        String database = normalizeDatabase(statementDatabase);
        if (database == null) {
            throw new DatabaseResolutionException(normalizedTable);
        }
        return database + "." + normalizedTable;
    }

    private String normalizeParts(String identifier) {
        if (identifier == null) {
            return null;
        }
        return identifier.trim().replace("`", "").toLowerCase(Locale.ROOT);
    }
}
