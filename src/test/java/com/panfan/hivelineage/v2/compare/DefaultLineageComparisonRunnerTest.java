package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.DialectSyntaxFrontend;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.export.LineageJsonRenderer;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.MetadataSnapshot;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;
import com.panfan.hivelineage.v2.testing.LegacyCorpusMetadataFixture;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultLineageComparisonRunnerTest {
    @Test
    public void twoEnginesUseFrozenMetadataWithBusinessIdentityPreserved() {
        AtomicInteger reads = new AtomicInteger();
        HiveMetadataProvider fixture = LegacyCorpusMetadataFixture.provider();
        HiveMetadataProvider live = table -> {
            reads.incrementAndGet();
            return fixture.getTableMetadata(table);
        };
        ScriptAnalysisRequest request = new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder().workflowId("wf").taskId("comparison")
            .taskVersion("BUSINESS_VERSION").defaultDatabase("default").rawScript("insert into test_a(a1,a2) select b1+1,b3 from test_b")
            .metadataProvider(live).options(LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build()).build());
        WorkflowContext workflow = new WorkflowContext("wf", request.getMetadataContext(), new DefaultV1MetadataAdapterFactory().adapt(
            new LegacyMetadataBinding(Optional.of(live), Optional.empty(), request.getMetadataContext(), false)), new DefaultMetadataCache());
        DefaultMetadataAccessFactory captures = new DefaultMetadataAccessFactory();
        DefaultScriptLineageAnalyzer analyzer = new DefaultScriptLineageAnalyzer(new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()),
            new DefaultSemanticBinder(), captures);
        ScriptAnalysisResult initial = analyzer.analyzeScript(request, workflow);
        assertEquals(0, initial.getCounts().getFailed());
        MetadataSnapshot snapshot = captures.create(workflow).snapshot();
        int before = reads.get();
        ComparisonResult comparison = new DefaultLineageComparisonRunner().compare(request, workflow, snapshot);
        assertEquals(before, reads.get());
        assertEquals("BUSINESS_VERSION", comparison.getV1().getTaskVersion());
        assertEquals("BUSINESS_VERSION", comparison.getV2().getTask().getTaskVersion());
        assertEquals(0, comparison.getV1().getFailedStatementCount());
        assertEquals(0, comparison.getV2().getCounts().getFailed());
        assertTrue(comparison.getReport().getDifferences().stream().allMatch(difference -> difference.getDisposition() == DifferenceDisposition.PENDING_REVIEW));
        assertTrue(new DefaultLineageDifferenceRenderer().render(comparison.getReport()).contains("total cases: 1"));
        String json = LineageJsonRenderer.render(comparison.getV2());
        assertTrue(json.contains("BUSINESS_VERSION"));
        assertTrue(json.contains("writeDependencies"));
        assertTrue(json.contains("\"role\":\"VALUE\""));
        assertTrue(json.contains("\"parents\""));
    }
}
