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

    private JoinCondition(Builder builder) {
        this.joinIndex = builder.joinIndex;
        this.queryBlockPath = builder.queryBlockPath;
        this.joinType = builder.joinType;
        this.joinTypeName = builder.joinTypeName;
        this.directJoinObjects = Collections.unmodifiableList(new ArrayList<>(builder.directJoinObjects));
        this.directJoinColumns = Collections.unmodifiableList(new ArrayList<>(builder.directJoinColumns));
        this.joinTables = Collections.unmodifiableList(new ArrayList<>(builder.joinTables));
        this.joinColumns = Collections.unmodifiableList(new ArrayList<>(builder.joinColumns));
        this.joinExpression = builder.joinExpression;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int joinIndex;
        private String queryBlockPath;
        private int joinType;
        private String joinTypeName;
        private List<DataObjectRef> directJoinObjects = Collections.emptyList();
        private List<ColumnRef> directJoinColumns = Collections.emptyList();
        private List<DataObjectRef> joinTables = Collections.emptyList();
        private List<ColumnRef> joinColumns = Collections.emptyList();
        private String joinExpression;

        public Builder joinIndex(int joinIndex) {
            this.joinIndex = joinIndex;
            return this;
        }

        public Builder queryBlockPath(String queryBlockPath) {
            this.queryBlockPath = queryBlockPath;
            return this;
        }

        public Builder joinType(int joinType) {
            this.joinType = joinType;
            return this;
        }

        public Builder joinTypeName(String joinTypeName) {
            this.joinTypeName = joinTypeName;
            return this;
        }

        public Builder directJoinObjects(List<DataObjectRef> directJoinObjects) {
            this.directJoinObjects = directJoinObjects;
            return this;
        }

        public Builder directJoinColumns(List<ColumnRef> directJoinColumns) {
            this.directJoinColumns = directJoinColumns;
            return this;
        }

        public Builder joinTables(List<DataObjectRef> joinTables) {
            this.joinTables = joinTables;
            return this;
        }

        public Builder joinColumns(List<ColumnRef> joinColumns) {
            this.joinColumns = joinColumns;
            return this;
        }

        public Builder joinExpression(String joinExpression) {
            this.joinExpression = joinExpression;
            return this;
        }

        public JoinCondition build() {
            return new JoinCondition(this);
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
