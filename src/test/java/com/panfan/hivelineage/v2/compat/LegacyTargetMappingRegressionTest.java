package com.panfan.hivelineage.v2.compat;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.analysis.Validity;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.api.StarMappingPolicy;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * V1 input/expected semantics copied from the original tests, with native assumptions exposed.
 */
public class LegacyTargetMappingRegressionTest {
    @Test
    public void noTargetMetadataInfersProjectionAndPartitionsOnlyUnderCurrentApiPolicy() {
        StatementLineageResult dynamic = analyze("insert overwrite table dwd.user_profile partition(dt) select user_id,dt from ods.user_base", null);
        assertEquals(2, dynamic.getColumnLineages().size());
        assertEquals("dwd.user_profile.dt", dynamic.getColumnLineages().get(1).getTargetColumn().getQualifiedName());
        assertEquals("ods.user_base.dt", dynamic.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName());
        StatementLineageResult fixed = analyze("insert overwrite table dwd.user_profile partition(dt='20260701') select user_id from ods.user_base", null);
        assertEquals(2, fixed.getColumnLineages().size());
        assertEquals("constant('20260701')", fixed.getColumnLineages().get(1).getExpression());
        ScriptAnalysisResult assumed = new HiveScriptLineageAdapter().analyzeNative(request("insert into dwd.user_profile select user_id from ods.user_base", null));
        assertEquals(Validity.UNDETERMINED, assumed.getStatements().get(0).getLineage().get().getQuality().getValidity());
        assertTrue(assumed.getDiagnostics().getDiagnostics().stream().anyMatch(diagnostic -> "CURRENT_API_TARGET_MAPPING".equals(diagnostic.getCode())));
        ScriptAnalysisResult exact = new HiveScriptLineageAdapter(new DefaultScriptLineageAnalyzer(),
                new DefaultRequestAdapter(StarMappingPolicy.POSITIONAL_EXACT), new DefaultLegacyResultProjector())
                .analyzeNative(request("insert into dwd.user_profile select user_id from ods.user_base", null));
        assertTrue(exact.getStatements().get(0).getBoundUnit().get().getWrites().get(0).getAssignments().isEmpty());
    }

    @Test
    public void nonStarKnownTargetPrefixIsNamedAssumptionWhileNativePositionsRemainExact() {
        HiveMetadataProvider provider = table -> HiveTableMetadata.table(table, table.getQualifiedName().equals("whc.c")
                ? Arrays.asList("field1", "id", "name", "value") : Arrays.asList("id", "field1"));
        StatementLineageResult current = analyze("insert into whc.c select whc.a.field1 from whc.a", provider);
        assertEquals(1, current.getColumnLineages().size());
        assertEquals("whc.c.field1", current.getColumnLineages().get(0).getTargetColumn().getQualifiedName());
        assertEquals("whc.a.field1", current.getColumnLineages().get(0).getSourceColumns().get(0).getQualifiedName());
        ScriptAnalysisResult strict = new HiveScriptLineageAdapter(new DefaultScriptLineageAnalyzer(),
                new DefaultRequestAdapter(StarMappingPolicy.POSITIONAL_EXACT), new DefaultLegacyResultProjector())
                .analyzeNative(request("insert into whc.c select whc.a.field1 from whc.a", provider));
        assertEquals(1, strict.getCounts().getFailed());
    }

    @Test
    public void unknownQualifiedStarsKeepDeclaredTargetsWithoutBorrowingAnySource() {
        HiveMetadataProvider provider = table -> HiveTableMetadata.table(table, Collections.singletonList("id"));
        for (String projection : Arrays.asList("missing.* from db1.a a",
                "x.* from db1.a a union all select x.* from db2.b x",
                "a.* from db1.a a union all select missing.* from db2.b b")) {
            StatementLineageResult current = analyze("insert into target.c select " + projection, provider);
            assertEquals(1, current.getColumnLineages().size());
            assertTrue(current.getColumnLineages().get(0).getSourceColumns().isEmpty());
            assertTrue(current.getWarnings().stream().anyMatch(warning -> "UNKNOWN_COLUMN_LINEAGE".equals(warning.getWarningCode().name())));
        }
    }

    @Test
    public void staticCtasAndViewIfNotExistsDoNotSuppressDeclaredPotentialLineage() {
        HiveMetadataProvider provider = table -> HiveTableMetadata.table(table, Arrays.asList("id", "name"));
        StatementLineageResult ctas = analyze("create table if not exists dwd.t as with x as(select id,name from ods.a) select * from x", provider);
        assertEquals(2, ctas.getColumnLineages().size());
        StatementLineageResult view = analyze("create view if not exists dwd.t(id,name) as select * from ods.a", provider);
        assertEquals(2, view.getColumnLineages().size());
    }

    @Test
    public void generatedFieldDoesNotBecomeAmbiguousWithUnknownPhysicalSchema() {
        StatementLineageResult result = analyze("insert into dwd.t(user_id,tag) select user_id,tag from ods.user_base lateral view explode(tags) tag_table as tag", null);
        assertEquals(2, result.getColumnLineages().size());
        assertEquals("ods.user_base.tags", result.getColumnLineages().get(1).getSourceColumns().get(0).getQualifiedName());
    }

    @Test
    public void unknownStructPathDoesNotInventAFieldWithALongerQualifiedOwner() {
        HiveMetadataProvider provider = table -> HiveTableMetadata.table(table,
                table.getQualifiedName().equals("target.c") ? Collections.singletonList("id") : Collections.singletonList("payload"));
        StatementLineageResult result = analyze("insert into target.c select a.payload.value+1 from db1.a a", provider);
        assertEquals(1, result.getColumnLineages().size());
        assertTrue(result.getColumnLineages().get(0).getSourceColumns().isEmpty());
        assertTrue(result.getWarnings().stream().anyMatch(warning -> "UNKNOWN_COLUMN_LINEAGE".equals(warning.getWarningCode().name())));
    }

    private StatementLineageResult analyze(String sql, HiveMetadataProvider provider) {
        LineageAnalyzeResult result = new HiveScriptLineageAdapter().analyzeScript(request(sql, provider));
        assertEquals(sql, 0, result.getFailedStatementCount());
        assertEquals(sql, 1, result.getStatements().size());
        return result.getStatements().get(0);
    }

    private LineageAnalyzeRequest request(String sql, HiveMetadataProvider provider) {
        return LineageAnalyzeRequest.builder().workflowId("wf").taskId("legacy-target").taskVersion("V1")
                .defaultDatabase("default").rawScript(sql).metadataProvider(provider)
                .options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).build()).build();
    }
}
