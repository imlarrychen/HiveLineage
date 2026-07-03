package com.panfan.hivelineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HiveTableMetadata {
    private final TableIdentifier tableIdentifier;
    private final HiveObjectType objectType;
    private final List<String> columns;
    private final String viewOriginalSql;

    private HiveTableMetadata(TableIdentifier tableIdentifier, HiveObjectType objectType, List<String> columns, String viewOriginalSql) {
        this.tableIdentifier = tableIdentifier;
        this.objectType = objectType;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
        this.viewOriginalSql = viewOriginalSql;
    }

    public static HiveTableMetadata table(TableIdentifier tableIdentifier, List<String> columns) {
        return new HiveTableMetadata(tableIdentifier, HiveObjectType.TABLE, columns, null);
    }

    public static HiveTableMetadata view(TableIdentifier tableIdentifier, List<String> columns, String viewOriginalSql) {
        return new HiveTableMetadata(tableIdentifier, HiveObjectType.VIEW, columns, viewOriginalSql);
    }

    public TableIdentifier getTableIdentifier() {
        return tableIdentifier;
    }

    public HiveObjectType getObjectType() {
        return objectType;
    }

    public List<String> getColumns() {
        return columns;
    }

    public String getViewOriginalSql() {
        return viewOriginalSql;
    }

    public boolean isView() {
        return objectType == HiveObjectType.VIEW;
    }
}
