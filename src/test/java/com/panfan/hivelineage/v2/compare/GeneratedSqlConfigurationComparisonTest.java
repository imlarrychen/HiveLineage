package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import com.panfan.hivelineage.result.StatementLineageResult;
import com.panfan.hivelineage.v2.api.DefaultScriptLineageAnalyzer;
import com.panfan.hivelineage.v2.api.ScriptAnalysisRequest;
import com.panfan.hivelineage.v2.common.QualifiedName;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.DefaultRequestAdapter;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.metadata.CapturedMetadataAccess;
import com.panfan.hivelineage.v2.metadata.DefaultMetadataCache;
import com.panfan.hivelineage.v2.metadata.DefaultV1MetadataAdapterFactory;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.LookupResult;
import com.panfan.hivelineage.v2.metadata.MetadataProvider;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.metadata.ProviderCapabilities;
import com.panfan.hivelineage.v2.metadata.RelationMetadata;
import com.panfan.hivelineage.v2.metadata.WorkflowContext;
import com.panfan.hivelineage.v2.testing.LegacyCorpusMetadataFixture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.Assert;
import org.junit.Test;

/**
 * Reuses the original fixture and configuration, independently of the broader mode matrix.
 */
public class GeneratedSqlConfigurationComparisonTest {
    @Test
    public void originalConfigurationsKeepCountsAndCompleteSinkProducts() throws Exception {
        List<CorpusCase> cases = corpus();
        Assert.assertEquals(18, cases.size());
        HiveScriptLineageAnalyzer v1 = new HiveScriptLineageAnalyzer();
        HiveScriptLineageAdapter v2 = new HiveScriptLineageAdapter();
        HiveMetadataProvider fixture = LegacyCorpusMetadataFixture.provider();
        HiveMetadataCache beforeCache = new HiveMetadataCache();
        HiveMetadataCache afterCache = new HiveMetadataCache();
        for (CorpusCase item : cases) {
            LineageAnalyzeRequest beforeRequest = request(item, fixture, beforeCache);
            LineageAnalyzeRequest afterRequest = request(item, fixture, afterCache);
            Assert.assertFalse(afterRequest.getOptions().isIncludeIgnoredStatements());
            Assert.assertFalse(afterRequest.getOptions().isParseJoinConditions());
            LineageAnalyzeResult before = v1.analyzeScript(beforeRequest);
            LineageAnalyzeResult after = v2.analyzeScript(afterRequest);
            Assert.assertEquals(item.getRelativePath(), counts(before), counts(after));
            Assert.assertEquals(item.getRelativePath(), item.getExpectedFailureCount(), after.getFailedStatementCount());
            Assert.assertEquals(item.getRelativePath(), products(before), products(after));
        }
    }

    /**
     * Produces unmodified full-differ reports; it never accepts or updates expected results.
     */
    public static void main(String[] args) throws Exception {
        new GeneratedSqlConfigurationComparisonTest().originalConfigurationsKeepCountsAndCompleteSinkProducts();
        Path directory = Paths.get("target/lineage-v2/comparison");
        Files.createDirectories(directory);
        StringBuilder summary = new StringBuilder("# Generated SQL original configuration comparison\n\n")
                .append("The original metadata fixture and all 18 SQL files are loaded dynamically. ")
                .append("Options: TABLE_AND_COLUMN, expandViewLineage=true, includeTemporaryLineage=true, ")
                .append("fallbackVariableValue=fallback_value; all other switches retain their original defaults. ")
                .append("Workflow=wf_generated; taskVersion=V1; taskId=file name without .sql.\n\n")
                .append("Both engines replay the union of their captured metadata reads with separate caches and sessions. ")
                .append("The complete LegacyStructureDiffer report retains every field difference as PENDING_REVIEW.\n\n")
                .append("| SQL | V1 counts total/ignored/failed | V2 counts | Differences | Fields |\n")
                .append("| --- | --- | --- | --- | --- |\n");
        int total = 0;
        for (CorpusCase item : corpus()) {
            ComparisonResult comparison = frozen(item);
            LineageAnalyzeResult projected = new DefaultLegacyResultProjector().project(comparison.getV2()).getResult();
            Assert.assertEquals(item.getRelativePath(), counts(comparison.getV1()), counts(projected));
            Assert.assertEquals(item.getRelativePath(), products(comparison.getV1()), products(projected));
            String report = new DefaultLineageDifferenceRenderer().render(comparison.getReport());
            String name = taskId(item) + "-original-config.md";
            Files.write(directory.resolve(name), report.getBytes(StandardCharsets.UTF_8));
            List<String> fields = comparison.getReport().getDifferences().stream().map(Difference::getDescription)
                    .map(detail -> detail.substring(0, detail.indexOf(':'))).collect(Collectors.toList());
            summary.append("| [").append(item.getRelativePath()).append("](").append(name).append(") | ")
                    .append(counts(comparison.getV1())).append(" | ").append(counts(projected)).append(" | ")
                    .append(fields.size()).append(" | ").append(String.join(", ", fields)).append(" |\n");
            total += fields.size();
        }
        summary.append("\nAll 18 cases have identical counts and sink mapping products. Full field differences: ")
                .append(total).append(". No expected snapshot was updated.\n");
        Files.write(directory.resolve("original-config-summary.md"), summary.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Generated original configuration: 18 cases; counts and sink products match; full differences=" + total);
    }

    private static ComparisonResult frozen(CorpusCase item) {
        HiveMetadataProvider fixture = LegacyCorpusMetadataFixture.provider();
        ScriptAnalysisRequest request = new DefaultRequestAdapter().adapt(request(item, fixture, new HiveMetadataCache()));
        MetadataProvider provider = new DefaultV1MetadataAdapterFactory().adapt(new LegacyMetadataBinding(Optional.of(fixture),
                Optional.empty(), request.getMetadataContext(), false));
        WorkflowContext workflow = new WorkflowContext("wf_generated", request.getMetadataContext(), provider, new DefaultMetadataCache());
        CapturedMetadataAccess capture = new CapturedMetadataAccess(workflow, 60_000);
        HiveMetadataProvider tracking = table -> {
            capture.lookupRelation(new QualifiedName(Arrays.asList(table.getDatabaseName(), table.getTableName())), request.getMetadataContext());
            return fixture.getTableMetadata(table);
        };
        new HiveScriptLineageAnalyzer().analyzeScript(request(item, tracking, new HiveMetadataCache()));
        MetadataProvider recording = new MetadataProvider() {
            @Override
            public ProviderCapabilities getCapabilities() {
                return provider.getCapabilities();
            }

            @Override
            public LookupResult<RelationMetadata> lookupRelation(QualifiedName name, MetadataReadContext context) {
                return capture.lookupRelation(name, context);
            }
        };
        new DefaultScriptLineageAnalyzer().analyzeScript(request,
                new WorkflowContext("wf_generated", request.getMetadataContext(), recording, new DefaultMetadataCache()));
        return new DefaultLineageComparisonRunner().compare(request, workflow, capture.snapshot());
    }

    private static List<CorpusCase> corpus() throws Exception {
        return LegacyCorpusMetadataFixture.legacyCorpus();
    }

    private static LineageAnalyzeRequest request(CorpusCase item, HiveMetadataProvider fixture, HiveMetadataCache cache) {
        return LineageAnalyzeRequest.builder().workflowId("wf_generated").taskId(taskId(item)).taskVersion("V1")
                .defaultDatabase("default").metadataProvider(fixture).metadataCache(cache).rawScript(item.getSource().getText())
                .options(LineageAnalyzeOptions.builder().parseMode(LineageParseMode.TABLE_AND_COLUMN).expandViewLineage(true)
                        .includeTemporaryLineage(true).fallbackVariableValue("fallback_value").build()).build();
    }

    private static String taskId(CorpusCase item) {
        return Paths.get(item.getRelativePath()).getFileName().toString().replace(".sql", "");
    }

    private static String counts(LineageAnalyzeResult result) {
        return result.getTotalStatementCount() + "/" + result.getIgnoredStatementCount() + "/" + result.getFailedStatementCount();
    }

    private static List<String> products(LineageAnalyzeResult result) {
        List<String> products = new ArrayList<String>();
        for (StatementLineageResult statement : result.getStatements()) {
            String prefix = statement.getStatementIndex() + ":" + statement.getStatementId() + ":" + statement.getParseStatusCode();
            products.add(prefix + ":" + statement.isFormalLineageProduced() + ":" + statement.getFailureCode());
            if (statement.getOutputObject() == null) {
                continue;
            }
            String target = statement.getOutputObject().getQualifiedName();
            products.add(prefix + ":" + statement.getStatementType() + ":" + target + ":" + statement.getParseMode());
            products.add(prefix + ":inputs:" + statement.getInputObjects().stream()
                    .map(object -> object.getQualifiedName()).collect(Collectors.toList()));
            statement.getTableLineages().stream().filter(table -> target.equals(table.getTargetObject().getQualifiedName()))
                    .forEach(table -> products.add(prefix + ":table:" + table.getOperation() + ":" + table.isThroughTemporaryTable()
                            + ":" + table.getSourceObjects().stream().map(object -> object.getQualifiedName()).collect(Collectors.toList())));
            statement.getColumnLineages().stream().filter(column -> column.getTargetColumn().getQualifiedName().startsWith(target + "."))
                    .forEach(column -> products.add(prefix + ":column:" + column.getTargetColumn().getQualifiedName() + ":"
                            + column.getSourceColumns().stream().map(field -> field.getQualifiedName()).collect(Collectors.toList()) + ":"
                            + column.getSourceObjects().stream().map(object -> object.getQualifiedName()).collect(Collectors.toList())));
        }
        return products;
    }
}
