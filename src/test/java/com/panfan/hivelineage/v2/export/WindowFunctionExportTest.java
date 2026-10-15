package com.panfan.hivelineage.v2.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WindowFunctionExportTest {
    @Test
    public void inlineDerivedAndNamedWindowsRetainFunctionsFromTheirOwnKeys() throws Exception {
        for (LineageParseMode mode : Arrays.asList(LineageParseMode.TABLE_AND_COLUMN, LineageParseMode.COLUMN_ONLY)) {
            ScriptAnalysisResult result = GeneratedSqlSupport.analyze("v2_lineage_export_window_nested_key_functions.sql", mode);
            assertEquals(0, result.getCounts().getFailed());
            assertEquals(4, result.getStatements().size());
            for (int index = 0; index < 3; index++) {
                assertFunctions(result, index, "rn", Arrays.asList("row_number", "lower", "abs"), ColumnDerivationKind.DERIVED);
                assertFunctions(result, index, "id", Collections.emptyList(), ColumnDerivationKind.DIRECT);
            }
            assertFunctions(result, 3, "amount", Arrays.asList("sum", "lower", "abs"), ColumnDerivationKind.DERIVED);
        }
    }

    private static void assertFunctions(ScriptAnalysisResult result, int index, String field,
                                        List<String> expected, ColumnDerivationKind kind) throws Exception {
        ColumnLineageRecord nativeColumn = new DefaultFormalLineageRecordExporter(
                new DefaultSourceRenderer(result.getSources()), false).exportColumns(result.getStatements().get(index)).stream()
                .filter(column -> "dwd.result".equals(column.getTarget().getObject().getDisplayName())
                        && Collections.singletonList(field).equals(column.getTarget().getFieldPath())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native column: " + field));
        assertEquals("Native functions: statement " + index, expected, nativeColumn.getFunctionNames());
        assertEquals(kind, nativeColumn.getDerivation());
        ColumnLineage legacyColumn = new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(index)
                .getColumnLineages().stream().filter(column -> ("dwd.result." + field)
                        .equals(column.getTargetColumn().getQualifiedName())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing compatibility column: " + field));
        assertEquals("Compatibility functions: statement " + index, expected, legacyColumn.getFunctionNames());
        assertEquals(LineageType.valueOf(kind.name()), legacyColumn.getLineageType());
        JsonNode columns = new ObjectMapper().readTree(LineageJsonRenderer.render(result)).get("statements").get(index).get("columnLineages");
        for (JsonNode column : columns) {
            JsonNode path = column.get("targetColumn").get("fieldPath");
            if (path.size() == 1 && field.equals(path.get(0).asText())) {
                List<String> functions = new ArrayList<>();
                column.get("functionNames").forEach(function -> functions.add(function.asText()));
                assertEquals("JSON functions: statement " + index, expected, functions);
                assertEquals(kind.name(), column.get("lineageType").asText());
                return;
            }
        }
        throw new AssertionError("Missing JSON column: " + field);
    }
}
