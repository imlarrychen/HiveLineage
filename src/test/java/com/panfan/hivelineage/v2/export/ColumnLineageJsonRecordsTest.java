package com.panfan.hivelineage.v2.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ColumnLineageJsonRecordsTest {
    @Test
    public void renderedColumnsExposeTheirTypesExpressionsAndNestedFunctions() throws Exception {
        JsonNode columns = columns("v2_expression_cast_nested_functions.sql", LineageParseMode.TABLE_AND_COLUMN);
        JsonNode name = target(columns, "name");
        assertEquals("DERIVED", name.get("lineageType").asText());
        assertEquals("concat", name.get("functionNames").get(0).asText());
        assertTrue(name.get("functionNames").toString().contains("upper"));
        assertTrue(name.get("functionNames").toString().contains("cast"));
        assertTrue(name.get("expression").asText().contains("concat"));
        assertTrue(name.get("expressionExtracted").asBoolean());
        assertEquals(2, name.get("sourceColumns").size());
        assertEquals("ods", name.get("sourceColumns").get(0).get("objectParts").get(0).asText());
        assertTrue(name.get("quality").has("coverage"));
    }

    @Test
    public void literalsAndCountStarWithNoSourceFieldsRemainDistinguishable() throws Exception {
        JsonNode literals = columns("v2_expression_constant_assignments.sql", LineageParseMode.COLUMN_ONLY);
        for (JsonNode column : literals) {
            assertEquals("CONSTANT", column.get("lineageType").asText());
            assertEquals(0, column.get("sourceColumns").size());
            assertEquals(0, column.get("functionNames").size());
        }
        JsonNode aggregates = columns("v2_expression_distinct_aggregate_count_star.sql", LineageParseMode.COLUMN_ONLY);
        assertEquals("DIRECT", target(aggregates, "id").get("lineageType").asText());
        JsonNode count = target(aggregates, "rn");
        assertEquals("DERIVED", count.get("lineageType").asText());
        assertEquals(0, count.get("sourceColumns").size());
        assertEquals("count", count.get("functionNames").get(0).asText());
    }

    @Test
    public void unknownFunctionRetainsItsNameAndPartialQuality() throws Exception {
        JsonNode score = target(columns("v2_expression_opaque_udf_arguments.sql",
            LineageParseMode.TABLE_AND_COLUMN), "amount");
        assertEquals("UNKNOWN", score.get("lineageType").asText());
        assertEquals("business_score", score.get("functionNames").get(0).asText());
        assertTrue(score.get("sourceColumns").size() > 0);
        assertEquals("PARTIAL", score.get("quality").get("coverage").asText());
    }

    @Test
    public void tableOnlyDoesNotPublishColumnClassifications() throws Exception {
        assertEquals(0, columns("v2_expression_cast_nested_functions.sql", LineageParseMode.TABLE_ONLY).size());
    }

    private JsonNode columns(String file, LineageParseMode mode) throws Exception {
        String json = LineageJsonRenderer.render(GeneratedSqlSupport.analyze(file, mode));
        return new ObjectMapper().readTree(json).get("statements").get(0).get("columnLineages");
    }

    private JsonNode target(JsonNode columns, String field) {
        for (JsonNode column : columns) {
            JsonNode path = column.get("targetColumn").get("fieldPath");
            if (field.equals(path.get(path.size() - 1).asText())) {
                return column;
            }
        }
        throw new AssertionError("Missing target: " + field);
    }
}
