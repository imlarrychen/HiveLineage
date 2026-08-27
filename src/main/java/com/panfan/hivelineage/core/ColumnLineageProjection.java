package com.panfan.hivelineage.core;

import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.LineageType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ColumnLineageProjection {
    final List<ColumnRef> sourceColumns;
    final String expression;
    final LineageType lineageType;
    final boolean unresolved;

    ColumnLineageProjection(List<ColumnRef> sourceColumns, String expression, LineageType lineageType) {
        this(sourceColumns, expression, lineageType, false);
    }

    ColumnLineageProjection(
        List<ColumnRef> sourceColumns,
        String expression,
        LineageType lineageType,
        boolean unresolved
    ) {
        this.sourceColumns = Collections.unmodifiableList(new ArrayList<>(sourceColumns));
        this.expression = expression;
        this.lineageType = lineageType;
        this.unresolved = unresolved;
    }

    List<ColumnRef> getSourceColumns() {
        return sourceColumns;
    }

    String getExpression() {
        return expression;
    }

    LineageType getLineageType() {
        return lineageType;
    }

    boolean isUnresolved() {
        return unresolved;
    }
}
