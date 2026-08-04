package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.DataObjectRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScriptAnalysisState {
    private final Map<String, List<DataObjectRef>> temporaryTableSources = new HashMap<>();
    private final Map<String, Map<String, ColumnLineageProjection>> temporaryTableColumns = new HashMap<>();
    private final Map<String, Map<String, ColumnLineageProjection>> viewColumnLineageCache = new HashMap<>();

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
