package com.panfan.hivelineage.core;

final class CteBlock {
    private final String name;
    private final String sql;

    CteBlock(String name, String sql) {
        this.name = name;
        this.sql = sql;
    }

    String getName() {
        return name;
    }

    String getSql() {
        return sql;
    }
}
