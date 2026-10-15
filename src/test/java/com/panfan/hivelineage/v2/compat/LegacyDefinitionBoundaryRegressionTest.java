package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.ColumnRef;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.result.TableLineage;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.export.DefaultFormalLineageRecordExporter;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;

public class LegacyDefinitionBoundaryRegressionTest {
    @Test
    public void generatedOutputUsesItsFunctionAndKeepsTheOriginalSqlAttachment() {
        String sql = "insert into dwd.t(name) select tag from ods.t lateral view explode(tags) exploded as tag";
        ScriptAnalysisResult result = nativeResult(sql, false);
        ColumnLineage column = legacy(result, 0).getColumnLineages().get(0);
        Assert.assertEquals("explode(tags)", column.getExpression());
        Assert.assertEquals(LineageType.DERIVED, column.getLineageType());
        Assert.assertEquals(Collections.singletonList("explode"), column.getFunctionNames());
        Assert.assertEquals(Collections.singleton("ods.t.tags"), columns(column));
        Assert.assertTrue(column.isComplexExpression());
        Assert.assertEquals(sql, legacy(result, 0).getRawSql());
        Assert.assertTrue(legacy(result, 0).getNormalizedSql().contains("select tag from"));
    }

    @Test
    public void expandedViewKeepsLegacyFieldBoundariesAndNativePhysicalProof() {
        ScriptAnalysisResult result = nativeResult("insert into dwd.t(name) select name from dim.v", false);
        StatementLineageResult statement = legacy(result, 0);
        ColumnLineage sink = target(statement, "dwd.t.name");
        ColumnLineage definition = target(statement, "dim.v.name");
        Assert.assertEquals(Collections.singleton("dim.v.name"), columns(sink));
        Assert.assertEquals("name", sink.getExpression());
        Assert.assertEquals(LineageType.DIRECT, sink.getLineageType());
        Assert.assertTrue(sink.getFunctionNames().isEmpty());
        Assert.assertEquals(Collections.singleton("ods.t.name"), columns(definition));
        Assert.assertEquals("upper(name)", definition.getExpression());
        Assert.assertEquals(LineageType.DERIVED, definition.getLineageType());
        Assert.assertTrue(new DefaultFormalLineageRecordExporter().exportColumns(result.getStatements().get(0)).stream()
                .filter(column -> "dwd.t".equals(column.getTarget().getObject().getDisplayName()))
                .flatMap(column -> column.getSources().stream())
                .anyMatch(column -> "ods.t".equals(column.getObject().getDisplayName())));
    }

    @Test
    public void mixedViewAndDirectPhysicalInputsDoNotLoseTheDirectFieldOfTheSameTable() {
        ScriptAnalysisResult result = nativeResult("insert into dwd.t(id) select v.id+b.id "
                + "from dim.v v join ods.t b on v.id=b.id", false);
        ColumnLineage sink = target(legacy(result, 0), "dwd.t.id");
        Assert.assertEquals(new LinkedHashSet<String>(Arrays.asList("dim.v.id", "ods.t.id")), columns(sink));
        Assert.assertEquals("v.id+b.id", sink.getExpression());
        Assert.assertEquals(LineageType.DERIVED, sink.getLineageType());
    }

    @Test
    public void includedTemporaryDefinitionsKeepOnlyTheNearestBoundaryAtEachEdge() {
        String sql = "create temporary table first_tmp as select id from ods.t; "
                + "create temporary table second_tmp as select id from first_tmp; "
                + "insert into dwd.t(id) select id from second_tmp";
        ScriptAnalysisResult result = nativeResult(sql, true);
        StatementLineageResult statement = legacy(result, 2);
        String first = "seele_tmp_db_wf_task.first_tmp.id";
        String second = "seele_tmp_db_wf_task.second_tmp.id";
        Assert.assertEquals(Collections.singleton(second), columns(target(statement, "dwd.t.id")));
        Assert.assertEquals(Collections.singleton(first), columns(target(legacy(result, 1), second)));
        Assert.assertEquals(Collections.singleton("ods.t.id"), columns(target(legacy(result, 0), first)));
        TableLineage secondTable = legacy(result, 1).getTableLineages().stream()
                .filter(table -> table.getTargetObject().getQualifiedName().endsWith(".second_tmp")).findFirst().get();
        Assert.assertEquals(Collections.singletonList("seele_tmp_db_wf_task.first_tmp"), secondTable.getSourceObjects()
                .stream().map(object -> object.getQualifiedName()).collect(Collectors.toList()));
        Assert.assertEquals(1, statement.getColumnLineages().size());
        ColumnLineage unfolded = target(legacy(nativeResult(sql, false), 0), "dwd.t.id");
        Assert.assertEquals(Collections.singleton("ods.t.id"), columns(unfolded));
    }

    private static Set<String> columns(ColumnLineage column) {
        return column.getSourceColumns().stream().map(ColumnRef::getQualifiedName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static ColumnLineage target(StatementLineageResult statement, String name) {
        return statement.getColumnLineages().stream()
                .filter(column -> name.equals(column.getTargetColumn().getQualifiedName())).findFirst().get();
    }

    private static StatementLineageResult legacy(ScriptAnalysisResult result, int index) {
        return new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(index);
    }

    private static ScriptAnalysisResult nativeResult(String sql, boolean includeTemporary) {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task")
                .rawScript(sql).defaultDatabase("default").options(LineageAnalyzeOptions.builder()
                        .parseMode(LineageParseMode.TABLE_AND_COLUMN).expandViewLineage(true)
                        .includeTemporaryLineage(includeTemporary).build())
                .metadataProvider(table -> "dim.v".equals(table.getQualifiedName())
                        ? HiveTableMetadata.view(table, Arrays.asList("id", "name"), "select id,upper(name) name from ods.t")
                        : HiveTableMetadata.table(table, Arrays.asList("id", "name", "tags"))).build();
        return new HiveScriptLineageAdapter().analyzeNative(request);
    }
}
