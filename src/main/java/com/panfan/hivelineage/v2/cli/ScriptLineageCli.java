package com.panfan.hivelineage.v2.cli;

import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.HiveMetadataProvider;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.output.LineageMarkdownRenderer;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.api.ScriptAnalysisResult;
import com.panfan.hivelineage.v2.compat.DefaultLegacyResultProjector;
import com.panfan.hivelineage.v2.compat.HiveScriptLineageAdapter;
import com.panfan.hivelineage.v2.export.LineageJsonRenderer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Independent CLI; the existing CLI entry and defaults are untouched.
 */
public final class ScriptLineageCli {
    private static final Logger LOGGER = Logger.getLogger(ScriptLineageCli.class.getName());

    private ScriptLineageCli() {
    }

    public static void main(String[] args) throws Exception {
        int status = run(args, LOGGER::info);
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] args, Consumer<String> messages) throws Exception {
        Path config = Paths.get(args.length == 0 ? "config/lineage-analyzer-v2.properties" : args[0]);
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(config)) {
            properties.load(stream);
        }
        Path script = Paths.get(required(properties, "lineage.scriptPath"));
        Path markdown = Paths.get(properties.getProperty("lineage.outputPath", "target/lineage-v2/result.md"));
        Path structured = Paths.get(properties.getProperty("lineage.v2.jsonOutputPath", "target/lineage-v2/result.json"));
        String database = properties.getProperty("lineage.defaultDatabase", "default");
        Map<String, String> variables = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("runtime.")) {
                variables.put(key.substring("runtime.".length()), properties.getProperty(key));
            }
        }
        HiveMetadataProvider provider = null;
        if (properties.containsKey("metadata.jdbc.url")) {
            provider = new JdbcMetadataColumnV2Provider(required(properties, "metadata.jdbc.url"), required(properties, "metadata.jdbc.user"),
                    properties.getProperty("metadata.jdbc.password", ""), properties.getProperty("metadata.columnTable", "metadata_column_v2"),
                    properties.getProperty("metadata.viewInfoTable", "hive_view_parse_info"));
        }
        LineageAnalyzeOptions options = LineageAnalyzeOptions.builder()
                .parseMode(LineageParseMode.valueOf(properties.getProperty("lineage.parseMode", "TABLE_AND_COLUMN")))
                .includeTemporaryLineage(bool(properties, "lineage.includeTemporaryLineage", true))
                .includeIgnoredStatements(bool(properties, "lineage.includeIgnoredStatements", false))
                .expandViewLineage(bool(properties, "lineage.expandViewLineage", true))
                .parseJoinConditions(bool(properties, "lineage.parseJoinConditions", false))
                .maxViewExpandDepth(Integer.parseInt(properties.getProperty("lineage.maxViewExpandDepth", "5")))
                .failFast(bool(properties, "lineage.failFast", false))
                .fallbackVariableValue(properties.getProperty("lineage.fallbackVariableValue", "__HIVE_LINEAGE_DEFAULT__")).build();
        LineageAnalyzeRequest request = LineageAnalyzeRequest.builder().workflowId(properties.getProperty("lineage.workflowId", "wf_local"))
                .taskId(properties.getProperty("lineage.taskId", script.getFileName().toString())).taskVersion(properties.getProperty("lineage.taskVersion", "V1"))
                .clusterName(properties.getProperty("lineage.clusterName", "default")).defaultDatabase(database)
                .rawScript(new String(Files.readAllBytes(script), StandardCharsets.UTF_8)).runtimeVariables(variables)
                .metadataProvider(provider).metadataCache(new HiveMetadataCache()).options(options).build();
        ScriptAnalysisResult result = new HiveScriptLineageAdapter().analyzeNative(request);
        write(markdown, LineageMarkdownRenderer.render(new DefaultLegacyResultProjector().project(result).getResult(), "Hive SQL Lineage V2", database));
        write(structured, LineageJsonRenderer.render(result));
        messages.accept("V2 output: " + markdown.toAbsolutePath());
        messages.accept("V2 structured output: " + structured.toAbsolutePath());
        messages.accept("Statements: total=" + result.getCounts().getTotal() + ", ignored=" + result.getCounts().getIgnored() + ", failed=" + result.getCounts().getFailed());
        return result.getCounts().getFailed() == 0 ? 0 : 2;
    }

    private static void write(Path path, String text) throws java.io.IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing configuration: " + key);
        }
        return value;
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, Boolean.toString(fallback)));
    }
}
