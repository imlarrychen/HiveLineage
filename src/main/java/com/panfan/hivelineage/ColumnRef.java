package com.panfan.hivelineage;

public final class ColumnRef {
    private final String qualifiedName;

    public ColumnRef(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }
}
