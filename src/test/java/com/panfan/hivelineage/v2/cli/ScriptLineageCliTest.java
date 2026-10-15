package com.panfan.hivelineage.v2.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScriptLineageCliTest {
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
        assertEquals(0, ScriptLineageCli.run(new String[]{config.toString()}, messages::add));
        assertTrue(new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8).contains("db.dest"));
        assertTrue(new String(Files.readAllBytes(json), StandardCharsets.UTF_8).contains("hive-lineage-v2"));
        assertEquals(3, messages.size());
        Files.write(sql, "insert into db.dest select from;".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, ScriptLineageCli.run(new String[]{config.toString()}, messages::add));
        ArrayList<String> command = new ArrayList<>();
        command.add(java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (!System.getProperty("java.specification.version").startsWith("1.")) {
            command.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ScriptLineageCli.class.getName());
        command.add(config.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve("cli-process.log").toFile()).start();
        assertEquals("The actual CLI process must preserve its failure exit code", 2, process.waitFor());
    }
}
