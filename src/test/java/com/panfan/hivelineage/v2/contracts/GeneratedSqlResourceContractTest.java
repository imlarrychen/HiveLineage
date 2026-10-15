package com.panfan.hivelineage.v2.contracts;

import com.panfan.hivelineage.v2.api.ExpressionLineageIntegrationTest;
import com.panfan.hivelineage.v2.api.NestedQueryIntegrationTest;
import com.panfan.hivelineage.v2.api.RelationalQueryIntegrationTest;
import com.panfan.hivelineage.v2.api.StatementLineageIntegrationTest;
import com.panfan.hivelineage.v2.compare.CorpusCase;
import com.panfan.hivelineage.v2.compare.DefaultGeneratedSqlCorpusLoader;
import com.panfan.hivelineage.v2.compat.LegacyColumnDerivationClassificationTest;
import com.panfan.hivelineage.v2.dialect.hive.HiveDdlSyntaxIntegrationTest;
import com.panfan.hivelineage.v2.dialect.hive.HiveKeywordSyntaxIntegrationTest;
import com.panfan.hivelineage.v2.export.ColumnDerivationExportTest;
import com.panfan.hivelineage.v2.session.DdlCatalogEffectsIntegrationTest;
import com.panfan.hivelineage.v2.testing.GeneratedSqlSupport;
import com.panfan.hivelineage.v2.testing.LegacyCorpusMetadataFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevents newly added SQL resources from escaping the explicit V2 integration matrix. */
public class GeneratedSqlResourceContractTest {
    private static final Path DIRECTORY = Paths.get("src/test/resources/hql/generated");

    @Test
    public void everyV2SqlResourceIsRegisteredInExactlyOneIntegrationSuite() throws IOException {
        Map<String, Set<String>> suites = new LinkedHashMap<>();
        suites.put("NestedQueryIntegrationTest", NestedQueryIntegrationTest.scenarioFiles());
        suites.put("RelationalQueryIntegrationTest", RelationalQueryIntegrationTest.scenarioFiles());
        suites.put("StatementLineageIntegrationTest", StatementLineageIntegrationTest.scenarioFiles());
        suites.put("ExpressionLineageIntegrationTest", ExpressionLineageIntegrationTest.scenarioFiles());
        suites.put("HiveDdlSyntaxIntegrationTest", HiveDdlSyntaxIntegrationTest.scenarioFiles());
        suites.put("DdlCatalogEffectsIntegrationTest", DdlCatalogEffectsIntegrationTest.scenarioFiles());
        suites.put("HiveKeywordSyntaxIntegrationTest", HiveKeywordSyntaxIntegrationTest.scenarioFiles());
        suites.put("LegacyColumnDerivationClassificationTest", LegacyColumnDerivationClassificationTest.scenarioFiles());
        suites.put("ColumnDerivationExportTest", ColumnDerivationExportTest.scenarioFiles());
        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> suite : suites.entrySet()) {
            assertFalse("Empty integration suite: " + suite.getKey(), suite.getValue().isEmpty());
            for (String file : suite.getValue()) {
                String previous = owners.put(file, suite.getKey());
                assertTrue(file + " is registered in both " + previous + " and " + suite.getKey(), previous == null);
            }
        }
        assertEquals("Each new SQL file needs one suite with independently reviewed expected lineage",
            v2SqlFiles(), new TreeSet<>(owners.keySet()));
    }

    @Test
    public void v2SqlResourcesHaveDescriptiveNamesAndNonemptyFileBackedContent() throws IOException {
        Set<String> files = v2SqlFiles();
        assertFalse("No V2 SQL scenarios were discovered", files.isEmpty());
        for (String file : files) {
            String name = Paths.get(file).getFileName().toString();
            assertTrue("Use a category and descriptive scenario words: " + file,
                name.matches("v2_[a-z][a-z0-9]*(?:_[a-z][a-z0-9]*){2,}\\.sql"));
            String text = new String(Files.readAllBytes(DIRECTORY.resolve(file)), StandardCharsets.UTF_8);
            assertFalse("Empty SQL scenario: " + file, text.trim().isEmpty());
            assertEquals("The integration suite must read the saved resource: " + file,
                text, GeneratedSqlSupport.readSql(file));
        }
    }

    @Test
    public void legacyBaselineStaysAt18WhileProductionLoaderDiscoversEverySqlResource() throws IOException {
        Set<String> originalFiles = new TreeSet<>();
        originalFiles.add("core_lineage_cases.sql");
        for (int index = 1; index <= 17; index++) {
            originalFiles.add(String.format(java.util.Locale.ROOT, "lineage_case_%02d.sql", index));
        }
        List<CorpusCase> legacy = LegacyCorpusMetadataFixture.legacyCorpus();
        assertEquals("Historical V1/V2 comparison keeps its original metadata and expected snapshots", 18, legacy.size());
        assertEquals(originalFiles, names(legacy));

        List<CorpusCase> discovered = new DefaultGeneratedSqlCorpusLoader().load(DIRECTORY);
        Set<String> allFiles = sqlFiles();
        assertEquals("Production discovery must retain all original and new SQL files", allFiles, names(discovered));
        assertEquals("Discovery must not duplicate resources", allFiles.size(), discovered.size());
        Set<String> combined = new TreeSet<>(originalFiles);
        combined.addAll(v2SqlFiles());
        assertEquals("Every resource belongs to the historical baseline or the new V2 matrix", allFiles, combined);
        for (CorpusCase item : discovered) {
            assertEquals(item.getRelativePath(),
                new String(Files.readAllBytes(DIRECTORY.resolve(item.getRelativePath())), StandardCharsets.UTF_8),
                item.getSource().getText());
        }
    }

    private static Set<String> v2SqlFiles() throws IOException {
        return sqlFiles().stream().filter(file -> Paths.get(file).getFileName().toString().startsWith("v2_"))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> sqlFiles() throws IOException {
        try (Stream<Path> files = Files.walk(DIRECTORY)) {
            return files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(".sql"))
                .map(file -> DIRECTORY.relativize(file).toString().replace(java.io.File.separatorChar, '/'))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> names(List<CorpusCase> corpus) {
        return corpus.stream().map(CorpusCase::getRelativePath).collect(Collectors.toCollection(TreeSet::new));
    }
}
