package com.panfan.hivelineage.v2.semantic;

/**
 * Clause identity uses stable path text and explicit output-alias visibility.
 */
enum ClauseLocation {
    SELECT("select", false), WHERE("where", false), ON("on", false), GROUP_BY("group_by", false),
    HAVING("having", true), ORDER_BY("order_by", true), SORT_BY("sort_by", true),
    DISTRIBUTE_BY("distribute_by", true), CLUSTER_BY("cluster_by", true);

    private final String path;
    private final boolean outputAliasesVisible;

    ClauseLocation(String path, boolean outputAliasesVisible) {
        this.path = path;
        this.outputAliasesVisible = outputAliasesVisible;
    }

    boolean outputAliasesVisible() {
        return outputAliasesVisible;
    }

    @Override
    public String toString() {
        return path;
    }
}
