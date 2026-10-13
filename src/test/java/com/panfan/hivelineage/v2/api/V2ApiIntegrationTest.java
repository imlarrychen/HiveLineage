package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.GeneratedV2MetadataFixture;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.compat.HiveV2LineageAnalyzer;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.ir.identity.ObjectId;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.DefinitionRef;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataFacet;
import com.panfan.hivelineage.v2.metadata.MetadataProvenance;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.MetadataVersion;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptor;
import com.panfan.hivelineage.v2.metadata.ObjectDescriptorProvider;
import com.panfan.hivelineage.v2.metadata.ProviderCapabilities;
import com.panfan.hivelineage.v2.metadata.RelationSchema;
import com.panfan.hivelineage.v2.metadata.RelationSchemaProvider;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.export.V2JsonRenderer;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class V2ApiIntegrationTest {
    @Test
    public void qualifiedJoinOnlyNeedsNoColumnMetadataIncludingUnrelatedStars() {
        V2AnalyzeRequest base = request("select a.*, b.* from db.t a join db.t b on a.id=b.id", LineageParseMode.TABLE_ONLY, null, true);
        V2AnalyzeRequest request = capabilities(base, EnumSet.of(AnalysisCapability.JOIN_CONDITION));
        AtomicInteger schemas = new AtomicInteger();
        WorkflowContext workflow = light(request, schemas);
        V2AnalyzeResult result = new DefaultV2LineageAnalyzer().analyzeScript(request, workflow);
        assertEquals(diagnostics(result), 0, result.getCounts().getFailed());
        assertEquals(0, schemas.get());
        assertFalse(result.getStatements().get(0).isFormalLineageProduced());
        assertFalse(result.getStatements().get(0).getLineage().isPresent());
        assertEquals(1, result.getStatements().get(0).getJoins().get().getJoins().size());
        assertEquals(2, result.getStatements().get(0).getJoins().get().getJoins().get(0).getDirectReferences().size());
        LineageAnalyzeResult legacy = new DefaultLegacyResultProjector().project(result).getResult();
        assertEquals(0, legacy.getStatements().get(0).getTableLineages().size());
        // CURRENT_API renders resolved physical names; self-join occurrences remain distinct in native facts.
        assertEquals(1, legacy.getStatements().get(0).getJoinConditions().get(0).getDirectJoinColumns().size());
    }

    @Test
    public void tableOnlyStarAndSubqueryTraverseObjectsWithoutSchema() {
        V2AnalyzeRequest request = request("insert into db.output_target select a.* from db.t a where exists(select 1 from db.u b where b.id=a.id)",
            LineageParseMode.TABLE_ONLY, null, false);
        AtomicInteger schemas = new AtomicInteger();
        V2AnalyzeResult result = new DefaultV2LineageAnalyzer().analyzeScript(request, light(request, schemas));
        assertEquals(diagnostics(result), 0, result.getCounts().getFailed());
        assertEquals(0, schemas.get());
        LineageAnalyzeResult projected = new DefaultLegacyResultProjector().project(result).getResult();
        assertEquals(2, projected.getStatements().get(0).getInputObjects().size());
        assertTrue(projected.getStatements().get(0).getColumnLineages().isEmpty());
    }

    @Test
    public void metadataViewUsesCreationDatabaseAndFailuresRecover() {
        HiveMetadataProvider provider = table -> table.getTableName().equals("v")
            ? HiveTableMetadata.view(table, Collections.singletonList("id"), "select id from base")
            : HiveTableMetadata.table(table, Collections.singletonList("id"));
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("V1")
            .defaultDatabase("wrong_database").metadataProvider(provider).rawScript("insert into output_target(id) select id from viewdb.v")
            .options(LineageAnalyzeOptions.builder().expandViewLineage(true).includeIgnoredStatements(true).build()).build();
        V2AnalyzeResult result = new HiveV2LineageAnalyzer().analyzeNative(request);
        assertEquals(diagnostics(result), 0, result.getCounts().getFailed());
        assertTrue(result.getStatements().get(0).getBoundUnit().get().getObjects().values().stream()
            .anyMatch(object -> object.getName().getDisplayName().equals("viewdb.base")));
        assertFalse(result.getStatements().get(0).getBoundUnit().get().getObjects().values().stream()
            .anyMatch(object -> object.getName().getDisplayName().equals("wrong_database.base")));
    }

    @Test
    public void setVariablesOverrideRuntimeAndNoStateLeaksIntoLaterRequest() {
        HiveV2LineageAnalyzer analyzer = new HiveV2LineageAnalyzer();
        HiveMetadataProvider provider = GeneratedV2MetadataFixture.provider();
        LineageAnalyzeRequest first = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("V1")
            .defaultDatabase("default").runtimeVariables(Collections.singletonMap("day", "old")).metadataProvider(provider)
            .rawScript("set day='new'; insert into test_a(a1) select '${hiveconf:day}' from test_b")
            .options(LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build()).build();
        V2AnalyzeResult firstResult = analyzer.analyzeNative(first);
        assertEquals(0, firstResult.getCounts().getFailed());
        assertTrue(firstResult.getStatements().get(1).getNormalizedSql().getText(), firstResult.getStatements().get(1).getNormalizedSql().getText().contains("'new'"));
        LineageAnalyzeRequest second = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("V2")
            .defaultDatabase("default").runtimeVariables(Collections.singletonMap("day", "old")).metadataProvider(provider)
            .rawScript("insert into test_a(a1) select '${day}' from test_b").build();
        assertTrue(analyzer.analyzeNative(second).getStatements().get(0).getNormalizedSql().getText().contains("'old'"));
    }

    @Test
    public void failFastKeepsScriptCountsAndRecoveryModeKeepsLaterStatement() {
        HiveMetadataProvider provider = GeneratedV2MetadataFixture.provider();
        String script = "insert into test_a(a1) select from; insert into test_a(a1) select b1 from test_b";
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("V1")
            .defaultDatabase("default").rawScript(script).metadataProvider(provider).options(LineageAnalyzeOptions.builder().failFast(true).build()).build();
        V2AnalyzeResult result = new HiveV2LineageAnalyzer().analyzeNative(request);
        assertEquals(2, result.getCounts().getTotal());
        assertEquals(1, result.getStatements().size());
        assertEquals(1, result.getCounts().getFailed());
    }

    @Test
    public void repeatedRunAndBatchTaskOrderingDoNotChangeSemantics() {
        HiveMetadataProvider provider = GeneratedV2MetadataFixture.provider();
        V2AnalyzeRequest first = request("use ods; insert into dwd.parse_recovery_target(user_id,user_name) select user_id,user_name from user_base", LineageParseMode.TABLE_AND_COLUMN, provider, false);
        V2AnalyzeRequest second = request("insert into dwd.parse_recovery_target(user_id,user_name) select user_id,user_name from ods.user_base", LineageParseMode.TABLE_AND_COLUMN, provider, false);
        com.panfan.hivelineage.v2.metadata.MetadataProvider adapted = new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(Optional.of(provider), Optional.empty(), first.getMetadataContext(), false));
        WorkflowContext workflow = new WorkflowContext("wf", first.getMetadataContext(), adapted, new DefaultMetadataCache());
        DefaultV2LineageAnalyzer analyzer = new DefaultV2LineageAnalyzer();
        String baseline = V2JsonRenderer.render(analyzer.analyzeScript(second, workflow));
        analyzer.analyzeScript(first, workflow);
        assertEquals(baseline, V2JsonRenderer.render(analyzer.analyzeScript(second, workflow)));
        WorkflowBatchResult batch = analyzer.analyze(new WorkflowBatchRequest("wf", Arrays.asList(first, second)), workflow);
        assertEquals(2, batch.getTasks().size());
        assertEquals(0, batch.getTasks().get(0).getCounts().getFailed());
        assertEquals(0, batch.getTasks().get(1).getCounts().getFailed());
    }

    private static String diagnostics(V2AnalyzeResult result) {
        return result.getDiagnostics().getDiagnostics().stream().map(diagnostic -> diagnostic.getCode() + ":" + diagnostic.getMessage()).collect(java.util.stream.Collectors.joining(";"));
    }

    private static V2AnalyzeRequest request(String sql, LineageParseMode mode, HiveMetadataProvider provider, boolean join) {
        return new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder().workflowId("wf").taskId("task").taskVersion("V1")
            .defaultDatabase("default").rawScript(sql).metadataProvider(provider).options(LineageAnalyzeOptions.builder().parseMode(mode)
                .parseJoinConditions(join).includeIgnoredStatements(true).expandViewLineage(true).build()).build());
    }

    private static V2AnalyzeRequest capabilities(V2AnalyzeRequest base, EnumSet<AnalysisCapability> capabilities) {
        AnalysisContract original = base.getContract();
        AnalysisContract contract = new AnalysisContract(new AnalysisCapabilities(capabilities), original.getVariablePolicy(), original.getDefinitionPolicy(),
            original.getStarMappingPolicy(), original.getPublicationPolicy(), original.isIncludeTemporaryLineage(), original.isIncludeIgnoredStatements(),
            original.isExpandViewLineage(), original.getMaxViewExpandDepth(), original.isFailFast(), original.getFallbackVariableValue());
        return new V2AnalyzeRequest(base.getTask(), base.getSource(), base.getDialect(), base.getInitialSession(), base.getMetadataContext(), contract, base.getResourceLimits());
    }

    private static WorkflowContext light(V2AnalyzeRequest request, AtomicInteger schemas) {
        ObjectDescriptorProvider objects = new ObjectDescriptorProvider() {
            @Override
            public ProviderCapabilities getCapabilities() {
                return new ProviderCapabilities(EnumSet.of(MetadataFacet.OBJECT_DESCRIPTOR), ProviderCapabilities.LookupShape.FACET_LOOKUP, false);
            }

            @Override
            public LookupResult<ObjectDescriptor> lookupObject(QualifiedName name, MetadataReadContext context) {
                return LookupResult.found(new ObjectDescriptor(new ObjectId(name.getDisplayName()), name, ObjectDescriptor.Kind.TABLE,
                    Optional.<DefinitionRef>empty(), new MetadataProvenance(context.getSourceId(), MetadataProvenance.Origin.TEST_FIXTURE,
                        Optional.<String>empty(), Optional.<Long>empty()), MetadataVersion.unknown()));
            }
        };
        RelationSchemaProvider columns = new RelationSchemaProvider() {
            @Override
            public ProviderCapabilities getCapabilities() {
                return new ProviderCapabilities(EnumSet.of(MetadataFacet.RELATION_SCHEMA), ProviderCapabilities.LookupShape.FACET_LOOKUP, false);
            }

            @Override
            public LookupResult<RelationSchema> lookupSchema(QualifiedName name, MetadataReadContext context) {
                schemas.incrementAndGet();
                throw new AssertionError("Unexpected schema read: " + name);
            }
        };
        return new WorkflowContext("wf", request.getMetadataContext(), Optional.empty(), new DefaultMetadataCache(), Optional.of(objects), Optional.of(columns));
    }
}
