package com.panfan.hivelineage.core;

final class DatabaseResolutionException extends RuntimeException {
    private final String tableName;

    DatabaseResolutionException(String tableName) {
        super("Database cannot be resolved for table " + tableName);
        this.tableName = tableName;
    }

    String getTableName() {
        return tableName;
    }
}
