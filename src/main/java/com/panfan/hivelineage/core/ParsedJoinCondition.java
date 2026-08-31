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

    private ParsedJoinCondition(Builder builder) {
        this.joinIndex = builder.joinIndex;
        this.queryBlockPath = builder.queryBlockPath;
        this.joinType = builder.joinType;
        this.joinTypeName = builder.joinTypeName;
        this.directJoinObjects = Collections.unmodifiableList(new ArrayList<>(builder.directJoinObjects));
        this.directJoinColumns = Collections.unmodifiableList(new ArrayList<>(builder.directJoinColumns));
        this.joinExpression = builder.joinExpression;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private int joinIndex;
        private String queryBlockPath;
        private int joinType;
        private String joinTypeName;
        private List<String> directJoinObjects = Collections.emptyList();
        private List<String> directJoinColumns = Collections.emptyList();
        private String joinExpression;

        Builder joinIndex(int joinIndex) {
            this.joinIndex = joinIndex;
            return this;
        }

        Builder queryBlockPath(String queryBlockPath) {
            this.queryBlockPath = queryBlockPath;
            return this;
        }

        Builder joinType(int joinType) {
            this.joinType = joinType;
            return this;
        }

        Builder joinTypeName(String joinTypeName) {
            this.joinTypeName = joinTypeName;
            return this;
        }

        Builder directJoinObjects(List<String> directJoinObjects) {
            this.directJoinObjects = directJoinObjects;
            return this;
        }

        Builder directJoinColumns(List<String> directJoinColumns) {
            this.directJoinColumns = directJoinColumns;
            return this;
        }

        Builder joinExpression(String joinExpression) {
            this.joinExpression = joinExpression;
            return this;
        }

        ParsedJoinCondition build() {
            return new ParsedJoinCondition(this);
        }
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
