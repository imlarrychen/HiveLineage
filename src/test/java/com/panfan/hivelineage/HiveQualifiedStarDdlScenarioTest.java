package com.panfan.hivelineage;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.JoinCondition;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

import static com.panfan.hivelineage.AnalyzerTestSupport.*;

public class HiveQualifiedStarDdlScenarioTest {
    private static final String SQL = "INSERT INTO whc.c \n"
        + "SELECT whc.a.field1, whc.b.* \n"
        + "FROM whc.a LEFT JOIN whc.b ON whc.a.id = whc.b.id;";
    private static final String[][] EXPECTED_MAPPINGS = {
        {"whc.c.field1", "whc.a.field1"},
        {"whc.c.id", "whc.b.id"},
        {"whc.c.name", "whc.b.name"},
        {"whc.c.value", "whc.b.value"}
    };

    @Test
    public void qualifiedStarDdlScenarioProducesExactDirectLineageAndJoin() throws Exception {
        TestMetadataProvider metadata = new TestMetadataProvider()
            .addTable("whc.a", "id", "field1")
            .addTable("whc.b", "id", "name", "value")
            .addTable("whc.c", "field1", "id", "name", "value");
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder()
            .workflowId("wf_sql_01")
            .taskId("task_sql_01")
            .taskVersion("V1")
            .defaultDatabase("whc")
            .metadataProvider(metadata)
            .metadataCache(new HiveMetadataCache())
            .rawScript(SQL)
            .options(LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.TABLE_AND_COLUMN)
                .parseJoinConditions(true)
                .build())
            .build();

        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(request);
        TestSupport.assertEquals(1, result.getStatements().size(), "sql_01 statement count");
        StatementLineageResult statement = result.getStatements().get(0);

        TestSupport.assertEquals(0, statement.getParseStatusCode(), "sql_01 parse status");
        TestSupport.assertEquals(0, statement.getWarnings().size(), "sql_01 warning count");
        assertExactDirectMappings(statement.getColumnLineages());
        assertExactJoin(statement);
        writeMarkdown(statement);
    }

    private void assertExactDirectMappings(List<ColumnLineage> columns) {
        TestSupport.assertEquals(EXPECTED_MAPPINGS.length, columns.size(), "sql_01 mapping count");
        for (int i = 0; i < EXPECTED_MAPPINGS.length; i++) {
            ColumnLineage column = columns.get(i);
            TestSupport.assertEquals(EXPECTED_MAPPINGS[i][0], column.getTargetColumn().getQualifiedName(), "target " + i);
            TestSupport.assertEquals(1, column.getSourceColumns().size(), "source count " + i);
            TestSupport.assertEquals(EXPECTED_MAPPINGS[i][1], column.getSourceColumns().get(0).getQualifiedName(), "source " + i);
            TestSupport.assertEquals(LineageType.DIRECT, column.getLineageType(), "lineage type " + i);
        }
    }

    private void assertExactJoin(StatementLineageResult statement) {
        TestSupport.assertEquals(1, statement.getJoinConditions().size(), "sql_01 join count");
        JoinCondition join = statement.getJoinConditions().get(0);
        TestSupport.assertEquals("whc.a.id = whc.b.id", join.getJoinExpression(), "sql_01 join expression");
        assertContainsColumn(join.getDirectJoinColumns(), "whc.a.id");
        assertContainsColumn(join.getDirectJoinColumns(), "whc.b.id");
        assertContainsSource(join.getJoinTables(), "whc.a");
        assertContainsSource(join.getJoinTables(), "whc.b");
        assertContainsColumn(join.getJoinColumns(), "whc.a.id");
        assertContainsColumn(join.getJoinColumns(), "whc.b.id");
    }

    private void writeMarkdown(StatementLineageResult statement) throws Exception {
        Path output = Paths.get("target/lineage-output/sql_01-lineage.md");
        Files.createDirectories(output.getParent());
        StringBuilder markdown = new StringBuilder()
            .append("# sql_01 Lineage Result\n\n")
            .append("## SQL\n\n```sql\n").append(SQL).append("\n```\n\n")
            .append("## Metadata\n\n")
            .append("- `whc.a(id, field1)`\n")
            .append("- `whc.b(id, name, value)`\n")
            .append("- `whc.c(field1, id, name, value)`\n\n")
            .append("## Column mappings\n\n");
        for (String[] mapping : EXPECTED_MAPPINGS) {
            markdown.append("- `").append(mapping[1]).append("` -> `")
                .append(mapping[0]).append("` (`DIRECT`)\n");
        }
        markdown.append("\n## Join\n\n")
            .append("- Expression: `whc.a.id = whc.b.id`\n")
            .append("- Tables: `whc.a`, `whc.b`\n")
            .append("- Columns: `whc.a.id`, `whc.b.id`\n\n")
            .append("## Status\n\n")
            .append("- Parse status: `").append(statement.getParseStatusCode()).append("` (success)\n")
            .append("- Warning count: `").append(statement.getWarnings().size()).append("`\n");
        Files.write(output, markdown.toString().getBytes(StandardCharsets.UTF_8));
    }
}
