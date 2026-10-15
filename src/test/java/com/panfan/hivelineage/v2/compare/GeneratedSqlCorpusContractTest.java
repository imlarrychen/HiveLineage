package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.HiveTableMetadata;
import com.panfan.hivelineage.metadata.TableIdentifier;
import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.metadata.LegacyMetadataBinding;
import com.panfan.hivelineage.v2.metadata.MetadataReadContext;
import com.panfan.hivelineage.v2.testing.LegacyCorpusMetadataFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

/** Reuses the actual corpus and provider without pretending a V2 SQL engine exists. */
public class GeneratedSqlCorpusContractTest {
    private static final Path CORPUS = Paths.get("src/test/resources/hql/generated");

    @Test
    public void existingGeneratedScriptsAreDirectInputsToV2CaseModels() throws IOException {
        List<CorpusCase> cases = corpusCases();
        Assert.assertTrue("All existing and future files are discovered", cases.size() >= 18);
        Assert.assertTrue(cases.stream().anyMatch(item -> item.getRelativePath().equals("core_lineage_cases.sql")));
        List<String> identities = new ArrayList<>();
        for (CorpusCase item : cases) {
            identities.add(item.getRelativePath());
            Assert.assertEquals(new String(Files.readAllBytes(CORPUS.resolve(item.getRelativePath())), StandardCharsets.UTF_8),
                item.getSource().getText());
            Assert.assertEquals("V1", item.getTask().getTaskVersion());
            Assert.assertEquals(item.getRelativePath(), item.getSource().getSourceId());
        }
        List<String> sorted = new ArrayList<>(identities);
        Collections.sort(sorted);
        Assert.assertEquals(sorted, identities);
        Assert.assertEquals(identities.size(), identities.stream().distinct().count());
    }

    @Test
    public void intentionalParseFailureRemainsPartOfTheCorpusContract() throws IOException {
        CorpusCase recovery = corpusCases().stream()
            .filter(item -> item.getRelativePath().equals("lineage_case_11.sql")).findFirst().get();
        Assert.assertEquals(1, recovery.getExpectedFailureCount());
        Assert.assertTrue(recovery.getSource().getText().contains("dwd.parse_recovery_target"));
        Assert.assertTrue(recovery.getSource().getText().contains("dwd.parse_error_target"));
    }

    @Test
    public void adapterBindingReusesTheOriginalFixtureAndCallerCache() {
        HiveMetadataProvider provider = LegacyCorpusMetadataFixture.provider();
        HiveMetadataCache cache = new HiveMetadataCache();
        MetadataReadContext context = new MetadataReadContext("generated-fixture", "default",
            Optional.empty(), Collections.emptyMap());
        LegacyMetadataBinding binding = new LegacyMetadataBinding(Optional.of(provider), Optional.of(cache), context, false);
        Assert.assertSame(provider, binding.getProvider().get());
        Assert.assertSame(cache, binding.getCache().get());
        Assert.assertFalse(binding.isAuthoritativeNullMeansNotFound());
        HiveTableMetadata source = provider.getTableMetadata(TableIdentifier.of("default", "ods", "user_base"));
        Assert.assertEquals("user_id", source.getColumns().get(0));
        HiveTableMetadata view = provider.getTableMetadata(TableIdentifier.of("default", "dim", "v_user_base"));
        Assert.assertTrue(view.isView());
        Assert.assertTrue(view.getViewOriginalSql().contains("ods.user_base"));
    }

    private List<CorpusCase> corpusCases() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(CORPUS)) {
            files = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".sql"))
                .sorted().collect(Collectors.toList());
        }
        List<CorpusCase> cases = new ArrayList<>();
        for (Path file : files) {
            String relative = CORPUS.relativize(file).toString();
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            TaskIdentity task = new TaskIdentity("wf_generated", relative, "V1");
            int expectedFailure = relative.equals("lineage_case_11.sql") ? 1 : 0;
            cases.add(new CorpusCase(relative, new SourceDocument(relative, text), task, expectedFailure));
        }
        return cases;
    }
}
