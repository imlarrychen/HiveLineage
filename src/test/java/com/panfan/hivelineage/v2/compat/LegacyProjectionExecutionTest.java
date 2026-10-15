package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.ColumnLineage;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.LineageType;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.api.AnalysisCapability;
import com.panfan.hivelineage.v2.api.AnalysisCapabilities;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.export.DefaultFormalLineageRecordExporter;
import com.panfan.hivelineage.v2.export.DefaultJoinConditionRecordExporter;
import com.panfan.hivelineage.v2.export.DefaultLineageRecordExporter;
import com.panfan.hivelineage.v2.export.DefaultSourceRenderer;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;

import org.junit.Assert;
import org.junit.Test;

public class LegacyProjectionExecutionTest {
    @Test
    public void directDerivedAndConstantColumnsUseDeclaredNamesAndOriginalExpressions() {
        StatementLineageResult statement = analyze("insert into dwd.t(id,amount,name) "
                + "select a.id, a.amount * a.qty, 'fixed' from ods.t a").getStatements().get(0);
        Assert.assertEquals(Integer.valueOf(0), statement.getParseStatusCode());
        Assert.assertEquals("dwd.t.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        Assert.assertEquals("ods.t.id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName());
        Assert.assertEquals("a.id", statement.getColumnLineages().get(0).getExpression());
        Assert.assertEquals("a.amount * a.qty", statement.getColumnLineages().get(1).getExpression());
        Assert.assertEquals("constant('fixed')", statement.getColumnLineages().get(2).getExpression());
        Assert.assertEquals(LineageType.CONSTANT, statement.getColumnLineages().get(2).getLineageType());
    }

    @Test
    public void countStarKeepsTablesWithoutFabricatedConditionColumns() {
        StatementLineageResult statement = analyze("insert into dwd.t(amount) select count(*) from ods.t where qty>0")
                .getStatements().get(0);
        ColumnLineage count = statement.getColumnLineages().get(0);
        Assert.assertTrue(count.getSourceColumns().isEmpty());
        Assert.assertEquals("ods.t", count.getSourceObjects().get(0).getQualifiedName());
        Assert.assertEquals("count(*)", count.getExpression());
    }

    @Test
    public void windowOrderAndPartitionColumnsArePreservedByCompatibilityProjection() {
        StatementLineageResult statement = analyze("insert into dwd.t(id,rn) "
                + "select id, row_number() over(partition by id order by amount desc) from ods.t").getStatements().get(0);
        ColumnLineage window = statement.getColumnLineages().get(1);
        Assert.assertEquals(2, window.getSourceColumns().size());
        Assert.assertTrue(window.getFunctionNames().contains("row_number"));
        Assert.assertTrue(window.getExpression().contains("partition by id"));
    }

    @Test
    public void unqualifiedJoinNamesRemainDirectWhilePhysicalNamesAreQualified() {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().rawScript(
                        "select * from ods.left_table l join ods.right_table r on user_id=manager_id")
                .options(LineageAnalyzeOptions.builder().parseJoinConditions(true).build())
                .metadataProvider(table -> HiveTableMetadata.table(table,
                        table.getTableName().equals("left_table") ? Arrays.asList("user_id") : Arrays.asList("manager_id"))).build();
        com.panfan.hivelineage.result.JoinCondition join = new HiveScriptLineageAdapter().analyzeScript(request)
                .getStatements().get(0).getJoinConditions().get(0);
        Assert.assertEquals("user_id", join.getDirectJoinColumns().get(0).getQualifiedName());
        Assert.assertEquals("manager_id", join.getDirectJoinColumns().get(1).getQualifiedName());
        Assert.assertEquals("ods.left_table.user_id", join.getJoinColumns().get(0).getQualifiedName());
        Assert.assertEquals("ods.right_table.manager_id", join.getJoinColumns().get(1).getQualifiedName());
    }

    @Test
    public void multiTargetProjectionKeepsAllEdgesAndReportsTheSingularOutputLimitation() {
        ScriptAnalysisResult nativeResult = nativeResult("from ods.t a insert into dwd.x(id) select a.id "
                + "insert into dwd.y(name) select a.name");
        LegacyProjection projection = new DefaultLegacyResultProjector().project(nativeResult);
        StatementLineageResult statement = projection.getResult().getStatements().get(0);
        Assert.assertEquals(2, statement.getTableLineages().size());
        Assert.assertEquals(2, statement.getColumnLineages().size());
        Assert.assertEquals("MULTIPLE_TARGET_OBJECTS", projection.getLimitations().get(0).getCode());
        Assert.assertTrue(statement.getWarnings().get(0).getMessage().contains("MULTIPLE_TARGET_OBJECTS"));
    }

    @Test
    public void setOperationExpressionsAreRenderedFromBranchSlots() {
        StatementLineageResult statement = analyze("insert into dwd.t(id) select id from ods.t union all select id from ods.u")
                .getStatements().get(0);
        Assert.assertEquals("union_all(ods.t.id, ods.u.id)", statement.getColumnLineages().get(0).getExpression());
    }

    @Test
    public void distinctAndQualifiedSetStarsAreRenderedThroughIrSlots() {
        StatementLineageResult distinct = analyze("insert into dwd.t(id) select a.id from ods.t a union select b.id from ods.u b")
                .getStatements().get(0);
        Assert.assertEquals("union_distinct(ods.t.id, ods.u.id)", distinct.getColumnLineages().get(0).getExpression());
        StatementLineageResult intersection = analyze("insert into dwd.t select a.* from ods.t a intersect select b.* from ods.u b")
                .getStatements().get(0);
        Assert.assertEquals("intersect(ods.t.id, ods.u.id)", intersection.getColumnLineages().get(0).getExpression());
    }

    @Test
    public void derivedAggregateExpressionKeepsItsDefinitionRatherThanItsAlias() {
        StatementLineageResult statement = analyze("insert into dwd.t(amount) select x.total from "
                + "(select count(*) total from ods.t) x").getStatements().get(0);
        Assert.assertEquals("count(*)", statement.getColumnLineages().get(0).getExpression());
    }

    @Test
    public void staticPartitionsFollowProjectedFieldsInLegacyOrder() {
        StatementLineageResult statement = analyze("insert into dwd.t partition(dt='20261012') (id) select id from ods.t")
                .getStatements().get(0);
        Assert.assertEquals("dwd.t.id", statement.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        Assert.assertEquals("dwd.t.dt", statement.getColumnLineages().get(1).getTargetColumn().getQualifiedName());
        Assert.assertEquals("constant('20261012')", statement.getColumnLineages().get(1).getExpression());
    }

    @Test
    public void fullExportsReuseSinkFactsAndRetainStatementIdentity() {
        ScriptAnalysisResult result = nativeResult("insert into dwd.t(id) select a.id from ods.t a join ods.u b on a.id=b.id");
        DefaultFormalLineageRecordExporter exporter = new DefaultFormalLineageRecordExporter(new DefaultSourceRenderer(result.getSources()), false);
        Assert.assertEquals("dwd.t", exporter.exportTables(result.getStatements().get(0)).get(0).getTarget().getDisplayName());
        Assert.assertEquals("id", exporter.exportColumns(result.getStatements().get(0)).get(0).getTarget().getFieldPath().get(0));
        Assert.assertFalse(new DefaultLineageRecordExporter().export(result.getStatements().get(0)).isEmpty());
        Assert.assertEquals(result.getStatements().get(0).getIdentity(),
                new DefaultJoinConditionRecordExporter().export(result.getStatements().get(0)).get(0).getStatement());
        com.panfan.hivelineage.v2.export.JoinPresentation presentation = new DefaultJoinConditionRecordExporter(
                new DefaultSourceRenderer(result.getSources())).export(result.getStatements().get(0)).get(0).getPresentation().get();
        Assert.assertEquals("ods.t", presentation.getDirectObjects().get(0).getDisplayName());
        Assert.assertEquals("a", presentation.getDirectColumns().get(0).getObject().getDisplayName());
        Assert.assertEquals("id", presentation.getDirectColumns().get(0).getFieldPath().get(0));
        Assert.assertEquals("ods.t", presentation.getPhysicalTables().get(0).getDisplayName());
        Assert.assertEquals("a.id=b.id", presentation.getExpression().getText().get());
        Assert.assertTrue(presentation.getExpression().isExtracted());
    }

    @Test
    public void temporaryBoundaryOptionKeepsItsInputAndOutputEdges() {
        String script = "create temporary table tmp as select id,name from ods.t; "
                + "insert into dwd.t(id) select id from tmp";
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").rawScript(script)
                .defaultDatabase("default").options(LineageAnalyzeOptions.builder().includeTemporaryLineage(true).build())
                .metadataProvider(table -> HiveTableMetadata.table(table, Arrays.asList("id", "name"))).build();
        ScriptAnalysisResult nativeResult = new HiveScriptLineageAdapter().analyzeNative(request);
        StatementLineageResult insert = new DefaultLegacyResultProjector().project(nativeResult).getResult()
                .getStatements().get(1);
        Assert.assertTrue(insert.getColumnLineages().get(0).getSourceColumns().stream()
                .anyMatch(field -> field.getQualifiedName().endsWith(".tmp.id")));
        StatementLineageResult create = new DefaultLegacyResultProjector().project(nativeResult).getResult().getStatements().get(0);
        Assert.assertTrue(create.getColumnLineages().stream().anyMatch(field ->
                field.getTargetColumn().getQualifiedName().endsWith(".tmp.id")
                        && field.getSourceColumns().stream().anyMatch(source -> "ods.t.id".equals(source.getQualifiedName()))));
        Assert.assertEquals(1, insert.getInputObjects().size());
        Assert.assertTrue(insert.getInputObjects().get(0).getQualifiedName().endsWith(".tmp"));
        Assert.assertTrue(insert.getTableLineages().get(0).isThroughTemporaryTable());
        DefaultFormalLineageRecordExporter formal = new DefaultFormalLineageRecordExporter(
                new DefaultSourceRenderer(nativeResult.getSources()), true);
        Assert.assertTrue(formal.exportColumns(nativeResult.getStatements().get(1)).stream()
                .anyMatch(field -> field.getTarget().getObject().getDisplayName().endsWith(".tmp")));
    }

    @Test
    public void expandedViewKeepsItsDirectInputSeparateFromItsPhysicalColumnSources() {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().rawScript(
                        "insert into dwd.t(id) select id from dim.v")
                .options(LineageAnalyzeOptions.builder().expandViewLineage(true).build())
                .metadataProvider(table -> table.getQualifiedName().equals("dim.v")
                        ? HiveTableMetadata.view(table, Arrays.asList("id"), "select id from ods.t")
                        : HiveTableMetadata.table(table, Arrays.asList("id"))).build();
        StatementLineageResult statement = new HiveScriptLineageAdapter().analyzeScript(request).getStatements().get(0);
        Assert.assertEquals(1, statement.getInputObjects().size());
        Assert.assertEquals("dim.v", statement.getInputObjects().get(0).getQualifiedName());
        Assert.assertEquals("VIEW_DEFINITION", statement.getTableLineages().get(0).getOperation());
        Assert.assertEquals("dim.v", statement.getTableLineages().get(1).getSourceObjects().get(0).getQualifiedName());
        Assert.assertEquals("dim.v.id", statement.getColumnLineages().stream()
                .filter(column -> "dwd.t.id".equals(column.getTargetColumn().getQualifiedName())).findFirst().get()
                .getSourceColumns().get(0).getQualifiedName());
    }

    @Test
    public void externalLoadPublishesItsUriWithoutInventingExternalColumns() {
        LineageAnalyzeRequest legacy = request("load data inpath 'hdfs://cluster/source/data.csv' into table dwd.t");
        ScriptAnalysisRequest base = new DefaultRequestAdapter().adapt(legacy);
        EnumSet<AnalysisCapability> capabilities = EnumSet.copyOf(base.getContract().getCapabilities().getCapabilities());
        capabilities.add(AnalysisCapability.EXTERNAL_DATA_LINEAGE);
        AnalysisContract old = base.getContract();
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(capabilities), old.getVariablePolicy(),
                old.getDefinitionPolicy(), old.getStarMappingPolicy(), old.getPublicationPolicy(), old.isIncludeTemporaryLineage(),
                old.isIncludeIgnoredStatements(), old.isExpandViewLineage(), old.getMaxViewExpandDepth(), old.isFailFast(),
                old.getFallbackVariableValue());
        ScriptAnalysisRequest request = new ScriptAnalysisRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(),
                base.getMetadataContext(), contract, base.getResourceLimits());
        WorkflowContext workflow = new WorkflowContext("wf", base.getMetadataContext(),
                new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(Optional.of(legacy.getMetadataProvider()),
                        Optional.empty(), base.getMetadataContext(), false)), new DefaultMetadataCache());
        ScriptAnalysisResult result = new DefaultScriptLineageAnalyzer().analyzeScript(request, workflow);
        StatementLineageResult projected = new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(0);
        Assert.assertEquals("hdfs://cluster/source/data.csv", projected.getInputObjects().get(0).getQualifiedName());
        Assert.assertEquals("hdfs://cluster/source/data.csv", projected.getTableLineages().get(0)
                .getSourceObjects().get(0).getQualifiedName());
        Assert.assertTrue(projected.getColumnLineages().stream().allMatch(column -> column.getSourceColumns().isEmpty()));
        Assert.assertFalse(result.getStatements().get(0).getBoundUnit().get().getWrites().get(0).getUnknownMappings().isEmpty());
        Assert.assertEquals("hdfs://cluster/source/data.csv", new DefaultFormalLineageRecordExporter()
                .exportTables(result.getStatements().get(0)).get(0).getSources().get(0).getDisplayName());
    }

    @Test
    public void caseDisplayNormalizesOperatorsWithoutChangingQuotedLiteralBytes() {
        StatementLineageResult statement = analyze("insert into dwd.t(id) select CASE WHEN id BETWEEN 1 AND 2 "
                + "THEN 'AND BETWEEN' ELSE 'ELSE' END from ods.t").getStatements().get(0);
        Assert.assertEquals("case when id between 1 and 2 then 'AND BETWEEN' else 'ELSE' end",
                statement.getColumnLineages().get(0).getExpression());
        Assert.assertTrue(statement.getNormalizedSql().contains("BETWEEN 1 AND 2"));
    }

    @Test
    public void compatibilityWarningsDoNotTurnUnrequestedWidthsIntoUnknownProjectedColumns() {
        String sql = "with tmp1 as(select id as renamed from ods.t) insert into dwd.t(id) "
                + "select x.renamed from tmp1 x join ods.u u on x.renamed=u.id";
        ScriptAnalysisResult result = nativeResult(sql);
        StatementLineageResult statement = new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(0);
        Assert.assertTrue(statement.getWarnings().isEmpty());
        Assert.assertEquals("ods.t.id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName());
    }

    @Test
    public void invalidDdlHasOneCompatibilityWarningAndPreservesNativeFrontendDiagnostics() {
        ScriptAnalysisResult result = nativeResult("alter table dwd.t set tblproperties（'comment'='bad'");
        StatementLineageResult statement = new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(0);
        Assert.assertEquals(Integer.valueOf(3), statement.getParseStatusCode());
        Assert.assertEquals(1, statement.getWarnings().size());
        Assert.assertEquals(com.panfan.hivelineage.result.WarningCode.INVALID_DDL, statement.getWarnings().get(0).getWarningCode());
        Assert.assertTrue(result.getStatements().get(0).getDiagnostics().getDiagnostics().size() > 1);
    }

    @Test
    public void parserFailureCodesAreAdaptedWithoutChangingNativeFailureEvidence() {
        ScriptAnalysisResult result = nativeResult("insert into dwd.t select from");
        Assert.assertEquals("SQL_PARSE_FAILED", result.getStatements().get(0).getFailureCode().get());
        Assert.assertEquals("UNSUPPORTED", result.getStatements().get(0).getStatementType());
        Assert.assertEquals("UNKNOWN", new DefaultLegacyResultProjector().project(result).getResult()
                .getStatements().get(0).getStatementType());
        Assert.assertEquals("SQL_PARSE_ERROR", new DefaultLegacyResultProjector().project(result).getResult()
                .getStatements().get(0).getFailureCode());
    }

    @Test
    public void unknownViewDefinitionDoesNotPublishAnEmptyLegacyDefinitionColumn() {
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().rawScript("insert into dwd.t(id) select v.id+1 from dim.v v")
                .options(LineageAnalyzeOptions.builder().expandViewLineage(true).build())
                .metadataProvider(table -> table.getQualifiedName().equals("dim.v")
                        ? HiveTableMetadata.view(table, Arrays.asList("id"), "select missing.id as id from ods.t")
                        : HiveTableMetadata.table(table, Arrays.asList("id"))).build();
        ScriptAnalysisResult result = new HiveScriptLineageAdapter().analyzeNative(request);
        StatementLineageResult statement = new DefaultLegacyResultProjector().project(result).getResult().getStatements().get(0);
        Assert.assertEquals(1, statement.getColumnLineages().size());
        Assert.assertEquals("dim.v.id", statement.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName());
        Assert.assertEquals(2, new DefaultFormalLineageRecordExporter().exportColumns(result.getStatements().get(0)).size());
    }

    @Test
    public void failedFallbackUseKeepsItsErrorSeparateFromCompatibilityWarnings() {
        StatementLineageResult statement = analyze("use ${missing_db}").getStatements().get(0);
        Assert.assertEquals("INVALID_USE_DATABASE", statement.getFailureCode());
        Assert.assertTrue(statement.getWarnings().isEmpty());
    }

    @Test
    public void partialSetMappingsKeepNativeProofAndApplyTheLegacyAtomicProjectionPolicy() {
        ScriptAnalysisResult result = nativeResult("with q as(select missing.id as id from ods.t union all "
                + "select a.id as id from ods.t a) insert into dwd.t(id) select q.id from q");
        LegacyProjection projected = new DefaultLegacyResultProjector().project(result);
        ColumnLineage column = projected.getResult().getStatements().get(0).getColumnLineages().get(0);
        Assert.assertTrue(column.getSourceColumns().isEmpty());
        Assert.assertTrue(column.getSourceObjects().isEmpty());
        Assert.assertTrue(projected.getLimitations().stream().anyMatch(value -> "ATOMIC_SET_MAPPING".equals(value.getCode())));
        Assert.assertEquals("ods.t", new DefaultFormalLineageRecordExporter().exportColumns(result.getStatements().get(0))
                .get(0).getSources().get(0).getObject().getDisplayName());
    }

    private LineageAnalyzeResult analyze(String sql) {
        return new HiveScriptLineageAdapter().analyzeScript(request(sql));
    }

    private ScriptAnalysisResult nativeResult(String sql) {
        return new HiveScriptLineageAdapter().analyzeNative(request(sql));
    }

    private LineageAnalyzeRequest request(String sql) {
        return LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("v1")
                .defaultDatabase("default").rawScript(sql).options(LineageAnalyzeOptions.builder()
                        .parseMode(LineageParseMode.TABLE_AND_COLUMN).parseJoinConditions(true).build())
                .metadataProvider(table -> HiveTableMetadata.table(table, Arrays.asList("id", "name", "amount", "qty", "rn", "dt", "tags")))
                .build();
    }
}
