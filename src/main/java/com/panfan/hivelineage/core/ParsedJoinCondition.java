package com.panfan.hivelineage.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ParsedJoinCondition {
    private final int joinIndex;
    private final String queryBlockPath;
    private final int joinType;
    private final String joinTypeName;
    private final List<String> directJoinObjects;
    private final List<String> directJoinColumns;
    private final String joinExpression;

    public ParsedJoinCondition(
        int joinIndex,
        String queryBlockPath,
        int joinType,
        String joinTypeName,
        List<String> directJoinObjects,
        List<String> directJoinColumns,
        String joinExpression
    ) {
        this.joinIndex = joinIndex;
        this.queryBlockPath = queryBlockPath;
        this.joinType = joinType;
        this.joinTypeName = joinTypeName;
        this.directJoinObjects = Collections.unmodifiableList(new ArrayList<>(directJoinObjects));
        this.directJoinColumns = Collections.unmodifiableList(new ArrayList<>(directJoinColumns));
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

    public List<String> getDirectJoinObjects() {
        return directJoinObjects;
    }

    public List<String> getDirectJoinColumns() {
        return directJoinColumns;
    }

    public String getJoinExpression() {
        return joinExpression;
    }
}
