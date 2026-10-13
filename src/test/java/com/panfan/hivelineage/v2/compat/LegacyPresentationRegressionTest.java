package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.V2AnalyzeResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Test;

public class LegacyPresentationRegressionTest {
    @Test
    public void normalizedDisplayRemovesTriviaWhileNativeEvidenceAndLiteralBytesArePreserved() {
        String sql = "/* lead */\ninsert into dwd.t(name) select '/* literal */--text' from ods.a";
        V2AnalyzeResult nativeResult = new HiveV2LineageAnalyzer().analyzeNative(request(sql));
        StatementLineageResult statement = new DefaultLegacyResultProjector().project(nativeResult).getResult().getStatements().get(0);
        Assert.assertEquals("insert into dwd.t(name) select '/* literal */--text' from ods.a", statement.getNormalizedSql());
        Assert.assertEquals(sql, statement.getRawSql());
        Assert.assertEquals(sql, nativeResult.getStatements().get(0).getNormalizedSql().getText());
        Assert.assertEquals("select id from ods.a", LegacySqlPresentation.normalized("select id/* comment */from ods.a"));
    }

    @Test
    public void legacyColumnObjectSourcesCoverWholeInputsEvenForSingleFieldAndConstantValues() {
        StatementLineageResult statement = new HiveV2LineageAnalyzer().analyzeScript(request(
                "insert into dwd.t(id,name) select a.id,'fixed' from ods.a a join ods.b b on a.id=b.id")).getStatements().get(0);
        for (ColumnLineage column : statement.getColumnLineages()) {
            Assert.assertEquals(Arrays.asList("ods.a", "ods.b"), column.getSourceObjects().stream()
                    .map(object -> object.getQualifiedName()).collect(Collectors.toList()));
        }
        Assert.assertEquals(Collections.singletonList("ods.a.id"), statement.getColumnLineages().get(0).getSourceColumns().stream()
                .map(field -> field.getQualifiedName()).collect(Collectors.toList()));
        Assert.assertTrue(statement.getColumnLineages().get(1).getSourceColumns().isEmpty());
    }

    @Test
    public void setOperationTagsKeepFunctionOccurrencesAndConstantDisplayRules() {
        StatementLineageResult statement = new HiveV2LineageAnalyzer().analyzeScript(request(
                "insert overwrite table dwd.t(id,name) select upper(name),'one' from ods.a "
                        + "union select upper(name),'two' from ods.b")).getStatements().get(0);
        Assert.assertEquals("INSERT_OVERWRITE", statement.getStatementType());
        ColumnLineage field = statement.getColumnLineages().get(0);
        Assert.assertEquals(Arrays.asList("union_distinct", "upper", "upper"), field.getFunctionNames());
        Assert.assertTrue(field.isComplexExpression());
        Assert.assertEquals(LineageType.DERIVED, field.getLineageType());
        ColumnLineage constant = statement.getColumnLineages().get(1);
        Assert.assertEquals(LineageType.CONSTANT, constant.getLineageType());
        Assert.assertTrue(constant.getFunctionNames().isEmpty());
        Assert.assertFalse(constant.isComplexExpression());
    }

    private static LineageAnalyzeRequest request(String sql) {
        return LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").rawScript(sql).defaultDatabase("default")
                .options(LineageAnalyzeOptions.builder().build())
                .metadataProvider(table -> HiveTableMetadata.table(table, Arrays.asList("id", "name"))).build();
    }
}
