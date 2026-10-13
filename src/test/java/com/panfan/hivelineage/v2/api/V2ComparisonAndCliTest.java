package com.panfan.hivelineage.v2.api;

import com.panfan.hivelineage.GeneratedV2MetadataFixture;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.v2.cli.LineageAnalyzeV2Cli;
import com.panfan.hivelineage.v2.compare.ComparisonResult;
import com.panfan.hivelineage.v2.compare.DefaultLineageComparisonRunner;
import com.panfan.hivelineage.v2.compare.DefaultLineageDifferenceRenderer;
import com.panfan.hivelineage.v2.compare.DifferenceDisposition;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.dialect.hive.HiveDialectFactory;
import com.panfan.hivelineage.v2.dialect.hive.HiveParseArtifact;
import com.panfan.hivelineage.v2.export.V2JsonRenderer;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataAccessFactory;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.MetadataSnapshot;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.semantic.DefaultSemanticBinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class V2ComparisonAndCliTest {
    @Test
    public void twoEnginesUseFrozenMetadataWithBusinessIdentityPreserved() {
        AtomicInteger reads = new AtomicInteger();
        HiveMetadataProvider fixture = GeneratedV2MetadataFixture.provider();
        HiveMetadataProvider live = table -> {
            reads.incrementAndGet();
            return fixture.getTableMetadata(table);
        };
        V2AnalyzeRequest request = new DefaultRequestAdapter().adapt(LineageAnalyzeRequest.builder().workflowId("wf").taskId("comparison")
            .taskVersion("BUSINESS_VERSION").defaultDatabase("default").rawScript("insert into test_a(a1,a2) select b1+1,b3 from test_b")
            .metadataProvider(live).options(LineageAnalyzeOptions.builder().includeIgnoredStatements(true).build()).build());
        WorkflowContext workflow = new WorkflowContext("wf", request.getMetadataContext(), new DefaultV1MetadataAdapterFactory().adapt(
            new LegacyMetadataBinding(Optional.of(live), Optional.empty(), request.getMetadataContext(), false)), new DefaultMetadataCache());
        DefaultMetadataAccessFactory captures = new DefaultMetadataAccessFactory();
        DefaultV2LineageAnalyzer analyzer = new DefaultV2LineageAnalyzer(new DialectSyntaxFrontend<HiveParseArtifact>(new HiveDialectFactory()),
            new DefaultSemanticBinder(), captures);
        V2AnalyzeResult initial = analyzer.analyzeScript(request, workflow);
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
        String json = V2JsonRenderer.render(comparison.getV2());
        assertTrue(json.contains("BUSINESS_VERSION"));
        assertTrue(json.contains("writeDependencies"));
        assertTrue(json.contains("\"role\":\"VALUE\""));
        assertTrue(json.contains("\"parents\""));
    }

    @Test
    public void independentCliWritesMarkdownAndStructuredResultWithoutChangingOldArtifacts() throws Exception {
        Path directory = Files.createTempDirectory("lineage-v2-cli");
        Path sql = directory.resolve("input.sql");
        Path config = directory.resolve("config.properties");
        Path markdown = directory.resolve("result.md");
        Path json = directory.resolve("result.json");
        Files.write(sql, "insert into db.dest(id) select a.id from db.src a;".getBytes(StandardCharsets.UTF_8));
        String properties = "lineage.scriptPath=" + sql + "\nlineage.outputPath=" + markdown
            + "\nlineage.v2.jsonOutputPath=" + json + "\nlineage.workflowId=wf\nlineage.taskId=cli\nlineage.taskVersion=V1\n";
        Files.write(config, properties.getBytes(StandardCharsets.UTF_8));
        ArrayList<String> messages = new ArrayList<>();
        assertEquals(0, LineageAnalyzeV2Cli.run(new String[] { config.toString() }, messages::add));
        assertTrue(new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8).contains("db.dest"));
        assertTrue(new String(Files.readAllBytes(json), StandardCharsets.UTF_8).contains("hive-lineage-v2"));
        assertEquals(3, messages.size());
        Files.write(sql, "insert into db.dest select from;".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, LineageAnalyzeV2Cli.run(new String[] { config.toString() }, messages::add));
        ArrayList<String> command = new ArrayList<>();
        command.add(java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (!System.getProperty("java.specification.version").startsWith("1.")) {
            command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(LineageAnalyzeV2Cli.class.getName());
        command.add(config.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(directory.resolve("cli-process.log").toFile()).start();
        assertEquals("The actual CLI process must preserve its failure exit code", 2, process.waitFor());
    }
}
