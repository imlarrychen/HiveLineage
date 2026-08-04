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

    ColumnLineageProjection(List<ColumnRef> sourceColumns, String expression, LineageType lineageType) {
            this.sourceColumns = Collections.unmodifiableList(new ArrayList<>(sourceColumns));
            this.expression = expression;
            this.lineageType = lineageType;
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
}
