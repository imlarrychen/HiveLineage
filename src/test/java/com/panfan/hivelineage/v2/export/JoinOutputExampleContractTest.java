package com.panfan.hivelineage.v2.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.DataObjectRef;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.api.StatementIdentity;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Original specification fixtures; expected values never originate from a V2 run.
 */
public class JoinOutputExampleContractTest {
    @Test
    public void cteJsonExampleMatchesEveryDeclaredFieldAcrossNativeAndLegacyViews() throws IOException {
        verify("cte-non-continuous",
                Arrays.asList(Arrays.asList("ods.a.id", "ods.b.id"), Arrays.asList("ods.c.id", "ods.d.id"), Arrays.asList("x.id", "y.id")));
    }

    @Test
    public void existsJsonExampleMatchesEveryDeclaredFieldAcrossNativeAndLegacyViews() throws IOException {
        verify("exists-non-continuous",
                Arrays.asList(Arrays.asList("ods.a.id", "ods.b.id"), Arrays.asList("ods.c.id", "ods.d.id")));
    }

    private void verify(String fixture, List<List<String>> legacyDirectColumns) throws IOException {
        String sql = resource(fixture + ".sql").trim();
        JsonNode expected = new ObjectMapper().readTree(resource(fixture + ".expected.json"));
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId(expected.get("workflowId").asText())
                .taskId(expected.get("taskId").asText()).taskVersion(expected.get("taskVersion").asText())
                .rawScript("use default;\n" + sql)
                .options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_ONLY).parseJoinConditions(true).build())
                .metadataProvider(table -> HiveTableMetadata.table(table, Collections.singletonList("id"))).build();
        ScriptAnalysisResult result = new HiveScriptLineageAdapter().analyzeNative(request);
        assertEquals(fixture, 0, result.getCounts().getFailed());
        List<StatementLineageResult> projected = new DefaultLegacyResultProjector().project(result).getResult().getStatements();
        assertEquals(1, projected.size());
        StatementLineageResult legacy = projected.get(0);
        assertEquals(expected.get("workflowId").asText(), legacy.getWorkflowId());
        assertEquals(expected.get("taskId").asText(), legacy.getTaskId());
        assertEquals(expected.get("taskVersion").asText(), legacy.getTaskVersion());
        assertEquals(expected.get("statementIndex").asInt(), legacy.getStatementIndex());
        assertEquals(expected.get("statementId").asText(), legacy.getStatementId());
        assertEquals(expected.get("formalLineageProduced").asBoolean(), legacy.isFormalLineageProduced());
        assertTrue(legacy.getTableLineages().isEmpty());
        assertTrue(legacy.getColumnLineages().isEmpty());
        assertTrue(expected.get("tableLineages").isEmpty());
        assertTrue(expected.get("columnLineages").isEmpty());
        // The original JSON abbreviates rawSql with (...); complete SQL is checked against its original SQL block.
        assertTrue(expected.get("rawSql").asText().contains("(...)"));
        assertEquals(sql, legacy.getRawSql());
        List<JoinConditionRecord> records = new DefaultJoinConditionRecordExporter().export(result.getStatements().get(1));
        JsonNode joins = expected.get("joinConditions");
        assertEquals(joins.size(), legacy.getJoinConditions().size());
        assertEquals(joins.size(), records.size());
        for (int index = 0; index < joins.size(); index++) {
            JsonNode item = joins.get(index);
            JoinConditionRecord record = records.get(index);
            assertIdentity(item, record.getStatement());
            assertEquals(sql, record.getRawSql().getText());
            assertTrue(record.getPresentation().isPresent());
            JoinPresentation nativeView = record.getPresentation().get();
            JoinCondition oldView = legacy.getJoinConditions().get(index);
            assertEquals(item.get("joinIndex").asInt(), record.getJoin().getJoinIndex());
            assertEquals(item.get("joinIndex").asInt(), oldView.getJoinIndex());
            assertEquals(item.get("queryBlockPath").asText(), record.getJoin().getQueryBlockPath());
            assertEquals(item.get("queryBlockPath").asText(), oldView.getQueryBlockPath());
            assertEquals(item.get("joinType").asInt(), nativeView.getTypeCode());
            assertEquals(item.get("joinType").asInt(), oldView.getJoinType());
            assertEquals(item.get("joinTypeName").asText(), nativeView.getTypeName());
            assertEquals(item.get("joinTypeName").asText(), oldView.getJoinTypeName());
            assertEquals(item.get("joinExpression").asText(), nativeView.getExpression().getText().get());
            assertEquals(item.get("joinExpression").asText(), oldView.getJoinExpression());
            assertTrue(nativeView.getExpression().isExtracted());
            // D-04: native direct fields retain aliases; legacy physical direct fields use resolved names.
            assertEquals(strings(item.get("directJoinObjects")), objects(oldView.getDirectJoinObjects()));
            assertEquals(strings(item.get("directJoinObjects")), qualified(nativeView.getDirectObjects()));
            assertEquals(strings(item.get("directJoinColumns")), addresses(nativeView.getDirectColumns()));
            assertEquals(legacyDirectColumns.get(index), columns(oldView.getDirectJoinColumns()));
            assertEquals(strings(item.get("joinTables")), qualified(nativeView.getPhysicalTables()));
            assertEquals(strings(item.get("joinTables")), objects(oldView.getJoinTables()));
            assertEquals(strings(item.get("joinColumns")), addresses(nativeView.getPhysicalColumns()));
            assertEquals(strings(item.get("joinColumns")), columns(oldView.getJoinColumns()));
        }
        assertFalse(result.getStatements().get(1).isFormalLineageProduced());
        assertFalse(result.getStatements().get(1).getLineage().isPresent());
    }

    private void assertIdentity(JsonNode expected, StatementIdentity actual) {
        assertEquals(expected.get("workflowId").asText(), actual.getTask().getWorkflowId());
        assertEquals(expected.get("taskId").asText(), actual.getTask().getTaskId());
        assertEquals(expected.get("taskVersion").asText(), actual.getTask().getTaskVersion());
        assertEquals(expected.get("statementIndex").asInt(), actual.getStatementIndex());
        assertEquals(expected.get("statementId").asText(), actual.getStatementId());
    }

    private String resource(String name) throws IOException {
        String location = "/v2/openspec/" + name;
        try (InputStream input = getClass().getResourceAsStream(location)) {
            assertNotNull(location, input);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asText());
        }
        return result;
    }

    private List<String> qualified(List<QualifiedName> values) {
        List<String> result = new ArrayList<>();
        for (QualifiedName value : values) {
            result.add(value.getDisplayName());
        }
        return result;
    }

    private List<String> addresses(List<ColumnAddress> values) {
        List<String> result = new ArrayList<>();
        for (ColumnAddress value : values) {
            result.add(value.getObject().getDisplayName() + "." + String.join(".", value.getFieldPath()));
        }
        return result;
    }

    private List<String> objects(List<DataObjectRef> values) {
        List<String> result = new ArrayList<>();
        for (DataObjectRef value : values) {
            result.add(value.getQualifiedName());
        }
        return result;
    }

    private List<String> columns(List<ColumnRef> values) {
        List<String> result = new ArrayList<>();
        for (ColumnRef value : values) {
            result.add(value.getQualifiedName());
        }
        return result;
    }
}
