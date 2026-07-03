package com.panfan.hivelineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TableLineage {
    private final DataObjectRef targetObject;
    private final List<DataObjectRef> sourceObjects;
    private final String operation;
    private final int statementIndex;
    private final boolean throughTemporaryTable;

    public TableLineage(
        DataObjectRef targetObject,
        List<DataObjectRef> sourceObjects,
        String operation,
        int statementIndex,
        boolean throughTemporaryTable
    ) {
        this.targetObject = targetObject;
        this.sourceObjects = Collections.unmodifiableList(new ArrayList<>(sourceObjects));
        this.operation = operation;
        this.statementIndex = statementIndex;
        this.throughTemporaryTable = throughTemporaryTable;
    }

    public DataObjectRef getTargetObject() {
        return targetObject;
    }

    public List<DataObjectRef> getSourceObjects() {
        return sourceObjects;
    }

    public String getOperation() {
        return operation;
    }

    public int getStatementIndex() {
        return statementIndex;
    }

    public boolean isThroughTemporaryTable() {
        return throughTemporaryTable;
    }
}
