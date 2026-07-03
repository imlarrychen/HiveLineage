package com.panfan.hivelineage.metadata;

import java.util.Locale;
import java.util.Objects;

public final class TableIdentifier {
    private final String clusterName;
    private final String databaseName;
    private final String tableName;

    private TableIdentifier(String clusterName, String databaseName, String tableName) {
        this.clusterName = normalize(clusterName == null ? "default" : clusterName);
        this.databaseName = normalize(databaseName == null ? "default" : databaseName);
        this.tableName = normalize(tableName);
    }

    public static TableIdentifier of(String clusterName, String databaseName, String tableName) {
        return new TableIdentifier(clusterName, databaseName, tableName);
    }

    public static TableIdentifier parse(String qualifiedName, String defaultCluster, String defaultDatabase) {
        String[] parts = qualifiedName.split("\\.");
        if (parts.length == 1) {
            return of(defaultCluster, defaultDatabase, parts[0]);
        }
        return of(defaultCluster, parts[0], parts[1]);
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getQualifiedName() {
        return databaseName + "." + tableName;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TableIdentifier)) {
            return false;
        }
        TableIdentifier that = (TableIdentifier) other;
        return Objects.equals(clusterName, that.clusterName)
            && Objects.equals(databaseName, that.databaseName)
            && Objects.equals(tableName, that.tableName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterName, databaseName, tableName);
    }
}
