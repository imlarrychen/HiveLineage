package com.panfan.hivelineage.v2.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class ScalarSubqueryFunctionExportTest {
    @Test
    public void scalarAggregateFunctionsReachTheFinalTargetInAllColumnResults() throws Exception {
        for (LineageParseMode mode : columnModes()) {
            ScriptAnalysisResult result = GeneratedSqlSupport.analyze("v2_nested_scalar_select_correlated.sql", mode);
            assertFunctions(result, "amount", Collections.singletonList("sum"));
            assertFunctions(result, "id", Collections.emptyList());
            assertFunctions(result, "name", Collections.emptyList());
        }
    }

    @Test
    public void scalarFunctionsInsideCaseAndCastRetainTheirExpressionOrder() throws Exception {
        for (LineageParseMode mode : columnModes()) {
            ScriptAnalysisResult result = GeneratedSqlSupport.analyze("v2_nested_scalar_case_cast_function.sql", mode);
            assertFunctions(result, "amount", Arrays.asList("avg", "cast", "max", "coalesce"));
            assertFunctions(result, "id", Collections.emptyList());
        }
    }

    @Test
    public void sortAndHavingSubqueryFunctionsDoNotPolluteProjectedValues() throws Exception {
        for (LineageParseMode mode : columnModes()) {
            ScriptAnalysisResult sorted = GeneratedSqlSupport.analyze("v2_keyword_nested_order_by_scalar_subquery.sql", mode);
            assertFunctions(sorted, "id", Collections.emptyList());
            ScriptAnalysisResult filtered = GeneratedSqlSupport.analyze("v2_nested_having_scalar_subquery.sql", mode);
            assertFunctions(filtered, "id", Collections.emptyList());
            assertFunctions(filtered, "amount", Collections.singletonList("sum"));
        }
    }

    private static List<LineageParseMode> columnModes() {
        return Arrays.asList(LineageParseMode.TABLE_AND_COLUMN, LineageParseMode.COLUMN_ONLY);
    }

    private static void assertFunctions(ScriptAnalysisResult result, String field, List<String> expected) throws Exception {
        ColumnLineageRecord nativeColumn = new DefaultFormalLineageRecordExporter(
                new DefaultSourceRenderer(result.getSources()), false).exportColumns(result.getStatements().get(0)).stream()
                .filter(column -> "dwd.result".equals(column.getTarget().getObject().getDisplayName())
                        && Collections.singletonList(field).equals(column.getTarget().getFieldPath())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing native target: " + field));
        Assert.assertEquals("Native functions for " + field, expected, nativeColumn.getFunctionNames());
        ColumnLineage legacyColumn = new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(0)
                .getColumnLineages().stream().filter(column -> ("dwd.result." + field).equals(column.getTargetColumn().getQualifiedName()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing compatibility target: " + field));
        Assert.assertEquals("Compatibility functions for " + field, expected, legacyColumn.getFunctionNames());
        JsonNode columns = new ObjectMapper().readTree(LineageJsonRenderer.render(result)).get("statements").get(0).get("columnLineages");
        for (JsonNode column : columns) {
            JsonNode path = column.get("targetColumn").get("fieldPath");
            if (path.size() == 1 && field.equals(path.get(0).asText())) {
                List<String> functions = new ArrayList<String>();
                column.get("functionNames").forEach(function -> functions.add(function.asText()));
                Assert.assertEquals("JSON functions for " + field, expected, functions);
                return;
            }
        }
        throw new AssertionError("Missing JSON target: " + field);
    }
}
