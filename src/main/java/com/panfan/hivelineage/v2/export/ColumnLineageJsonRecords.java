package com.panfan.hivelineage.v2.export;

import java.util.List;
import java.util.stream.Collectors;

/** Serializes formal column records without interpreting expression text or dependency evidence. */
final class ColumnLineageJsonRecords {
    private ColumnLineageJsonRecords() {
    }

    static String render(ColumnLineageRecord column) {
        return "{\"targetColumn\":" + address(column.getTarget())
            + ",\"sourceColumns\":[" + column.getSources().stream().map(ColumnLineageJsonRecords::address)
                .collect(Collectors.joining(",")) + "]"
            + ",\"lineageType\":" + LineageJsonRenderer.quote(column.getDerivation().name())
            + ",\"expression\":" + column.getExpression().getText().map(LineageJsonRenderer::quote).orElse("null")
            + ",\"expressionExtracted\":" + column.getExpression().isExtracted()
            + ",\"functionNames\":[" + strings(column.getFunctionNames()) + "]"
            + ",\"quality\":{\"validity\":" + LineageJsonRenderer.quote(column.getQuality().getValidity().name())
            + ",\"coverage\":" + LineageJsonRenderer.quote(column.getQuality().getCoverage().name())
            + ",\"precision\":" + LineageJsonRenderer.quote(column.getQuality().getPrecision().name()) + "}}";
    }

    private static String address(ColumnAddress column) {
        return "{\"objectParts\":[" + strings(column.getObject().getParts())
            + "],\"fieldPath\":[" + strings(column.getFieldPath()) + "]}";
    }

    private static String strings(List<String> values) {
        return values.stream().map(LineageJsonRenderer::quote).collect(Collectors.joining(","));
    }
}
