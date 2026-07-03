package com.panfan.hivelineage;

public final class DataObjectRef {
    private final String qualifiedName;

    public DataObjectRef(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }
}
