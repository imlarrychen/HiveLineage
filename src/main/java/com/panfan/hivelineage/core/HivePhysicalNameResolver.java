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
        String[] parts = normalizedTable.split("\\.");
        if (parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
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
