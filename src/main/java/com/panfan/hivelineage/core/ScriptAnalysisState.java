package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScriptAnalysisState {
    private final Map<String, List<DataObjectRef>> temporaryTableSources = new HashMap<>();
    private final Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns = new HashMap<>();
    private final Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache = new HashMap<>();
    private final HivePhysicalNameResolver nameResolver;
    private String currentDatabase;

    ScriptAnalysisState(String defaultDatabase, HivePhysicalNameResolver nameResolver) {
        if (nameResolver == null) {
            throw new IllegalArgumentException("nameResolver must not be null");
        }
        this.nameResolver = nameResolver;
        this.currentDatabase = isBlank(defaultDatabase) ? null : nameResolver.normalizeDatabase(defaultDatabase);
    }

    String getCurrentDatabase() {
        return currentDatabase;
    }

    void useDatabase(String database) {
        if (isBlank(database)) {
            throw new IllegalArgumentException("database must not be blank");
        }
        String normalizedDatabase = nameResolver.normalizeDatabase(database);
        if (normalizedDatabase == null) {
            throw new IllegalArgumentException("database must not be blank");
        }
        currentDatabase = normalizedDatabase;
    }

    private boolean isBlank(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    Map<String, List<DataObjectRef>> getTemporaryTableSources() {
        return temporaryTableSources;
    }

    Map<String, Map<String, ColumnLineageProjection>> getTemporaryTableColumns() {
        return temporaryTableColumns;
    }

    Map<String, Map<String, ColumnLineageProjection>> getViewColumnLineageCache() {
        return viewColumnLineageCache;
    }
}
