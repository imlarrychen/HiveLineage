package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.TaskIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads the existing corpus directly; all discovered files remain in the denominator.
 */
public final class DefaultGeneratedSqlCorpusLoader implements GeneratedSqlCorpusLoader {
    private final String workflowId;
    private final String taskVersion;
    private final Map<String, Integer> expectedFailures;

    public DefaultGeneratedSqlCorpusLoader() {
        this("wf_generated", "V1", Collections.singletonMap("lineage_case_11.sql", 1));
    }

    public DefaultGeneratedSqlCorpusLoader(String workflowId, String taskVersion, Map<String, Integer> expectedFailures) {
        this.workflowId = workflowId;
        this.taskVersion = taskVersion;
        this.expectedFailures = new java.util.LinkedHashMap<>(expectedFailures);
    }

    @Override
    public List<CorpusCase> load(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(directory)) {
            files = paths.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(java.util.Comparator.comparing(path -> directory.relativize(path).toString())).collect(Collectors.toList());
        }
        List<CorpusCase> cases = new ArrayList<>();
        for (Path file : files) {
            String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
            cases.add(new CorpusCase(relative, new SourceDocument(relative, new String(Files.readAllBytes(file), StandardCharsets.UTF_8)),
                    new TaskIdentity(workflowId, relative, taskVersion), expectedFailures.getOrDefault(relative, 0)));
        }
        return Collections.unmodifiableList(cases);
    }
}
