package com.panfan.hivelineage.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LineageAnalyzeCliTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void returnsSuccessCodeAndReportsSummaryWithoutUsingStandardOutput() throws Exception {
        Path scriptPath = writeFile("success.sql", "set hive.exec.dynamic.partition=true;");
        Path outputPath = temporaryFolder.newFile("success.md").toPath();
        Path configPath = writeConfig(scriptPath, outputPath);
        List<String> messages = new ArrayList<>();

        int exitCode = LineageAnalyzeCli.run(new String[] {configPath.toString()}, messages::add);

        assertEquals(0, exitCode);
        assertTrue(messages.contains("Lineage parse finished."));
        assertTrue(messages.get(messages.size() - 1).contains("failed=0"));
    }

    @Test
    public void returnsFailureCodeWhenAnyStatementFails() throws Exception {
        Path scriptPath = writeFile("failure.sql", "unsupported command;");
        Path outputPath = temporaryFolder.newFile("failure.md").toPath();
        Path configPath = writeConfig(scriptPath, outputPath);

        int exitCode = LineageAnalyzeCli.run(new String[] {configPath.toString()}, message -> { });

        assertEquals(2, exitCode);
    }

    private Path writeConfig(Path scriptPath, Path outputPath) throws Exception {
        return writeFile(
            "lineage.properties",
            "lineage.scriptPath=" + scriptPath + "\n"
                + "lineage.outputPath=" + outputPath + "\n"
                + "metadata.jdbc.url=jdbc:mysql://localhost:3306/metadata\n"
                + "metadata.jdbc.user=test\n"
                + "metadata.jdbc.password=test\n"
        );
    }

    private Path writeFile(String name, String content) throws Exception {
        Path path = temporaryFolder.newFile(name).toPath();
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
