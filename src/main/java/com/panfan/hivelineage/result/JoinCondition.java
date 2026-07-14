package com.panfan.hivelineage.result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class JoinCondition {
    private final int joinIndex;
    private final String queryBlockPath;
    private final int joinType;
    private final String joinTypeName;
    private final List<DataObjectRef> directJoinObjects;
    private final List<ColumnRef> directJoinColumns;
    private final List<DataObjectRef> joinTables;
    private final List<ColumnRef> joinColumns;
    private final String joinExpression;

    public JoinCondition(
        int joinIndex,
        String queryBlockPath,
        int joinType,
        String joinTypeName,
        List<DataObjectRef> directJoinObjects,
        List<ColumnRef> directJoinColumns,
        List<DataObjectRef> joinTables,
        List<ColumnRef> joinColumns,
        String joinExpression
    ) {
        this.joinIndex = joinIndex;
        this.queryBlockPath = queryBlockPath;
        this.joinType = joinType;
        this.joinTypeName = joinTypeName;
        this.directJoinObjects = Collections.unmodifiableList(new ArrayList<>(directJoinObjects));
        this.directJoinColumns = Collections.unmodifiableList(new ArrayList<>(directJoinColumns));
        this.joinTables = Collections.unmodifiableList(new ArrayList<>(joinTables));
        this.joinColumns = Collections.unmodifiableList(new ArrayList<>(joinColumns));
        this.joinExpression = joinExpression;
    }

    public int getJoinIndex() {
        return joinIndex;
    }

    public String getQueryBlockPath() {
        return queryBlockPath;
    }

    public int getJoinType() {
        return joinType;
    }

    public String getJoinTypeName() {
        return joinTypeName;
    }

    public List<DataObjectRef> getDirectJoinObjects() {
        return directJoinObjects;
    }

    public List<ColumnRef> getDirectJoinColumns() {
        return directJoinColumns;
    }

    public List<DataObjectRef> getJoinTables() {
        return joinTables;
    }

    public List<ColumnRef> getJoinColumns() {
        return joinColumns;
    }

    public String getJoinExpression() {
        return joinExpression;
    }
}
