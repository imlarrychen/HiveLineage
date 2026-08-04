package com.panfan.hivelineage.core;

final class DirectColumn {
    private final String owner;
    private final String column;

    DirectColumn(String owner, String column) {
        this.owner = owner;
        this.column = column;
    }

    String getOwner() {
        return owner;
    }

    String getColumn() {
        return column;
    }
}
