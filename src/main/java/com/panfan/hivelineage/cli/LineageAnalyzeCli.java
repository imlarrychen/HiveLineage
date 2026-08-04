package com.panfan.hivelineage.cli;

import com.panfan.hivelineage.core.HiveScriptLineageAnalyzer;
import com.panfan.hivelineage.metadata.HiveMetadataCache;
import com.panfan.hivelineage.metadata.JdbcMetadataColumnV2Provider;
import com.panfan.hivelineage.output.LineageMarkdownRenderer;
import com.panfan.hivelineage.request.LineageAnalyzeOptions;
import com.panfan.hivelineage.request.LineageAnalyzeRequest;
import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.result.LineageAnalyzeResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class LineageAnalyzeCli {
    private static final String DEFAULT_CONFIG_PATH = "config/lineage-analyzer.properties";
    private static final Logger LOGGER = Logger.getLogger(LineageAnalyzeCli.class.getName());

    private LineageAnalyzeCli() {
    }

    public static void main(String[] args) throws Exception {
        int exitCode = run(args, LOGGER::info);
        if (exitCode != 0) {
            throw new IllegalStateException("Lineage analysis completed with exit code " + exitCode);
        }
    }

    static int run(String[] args, Consumer<String> messageConsumer) throws Exception {
        Path configPath = Paths.get(args.length == 0 ? DEFAULT_CONFIG_PATH : args[0]);
        if (!Files.exists(configPath)) {
            throw new IllegalArgumentException("Config file not found: " + configPath.toAbsolutePath()
                + ". Copy config/lineage-analyzer.example.properties and adjust it first.");
        }

        Properties properties = loadProperties(configPath);
        Path scriptPath = Paths.get(required(properties, "lineage.scriptPath"));
        Path outputPath = Paths.get(get(properties, "lineage.outputPath", "target/lineage-output/lineage-result.md"));
        String defaultDatabase = get(properties, "lineage.defaultDatabase", "default");
        String script = new String(Files.readAllBytes(scriptPath), StandardCharsets.UTF_8);

        LineageAnalyzeOptions options = LineageAnalyzeOptions.builder()
            .parseMode(LineageParseMode.valueOf(get(properties, "lineage.parseMode", "TABLE_AND_COLUMN")))
            .includeTemporaryLineage(getBoolean(properties, "lineage.includeTemporaryLineage", true))
            .includeIgnoredStatements(getBoolean(properties, "lineage.includeIgnoredStatements", false))
            .expandViewLineage(getBoolean(properties, "lineage.expandViewLineage", true))
            .maxViewExpandDepth(getInt(properties, "lineage.maxViewExpandDepth", 5))
            .failFast(getBoolean(properties, "lineage.failFast", false))
            .fallbackVariableValue(get(properties, "lineage.fallbackVariableValue", "__HIVE_LINEAGE_DEFAULT__"))
            .build();

        JdbcMetadataColumnV2Provider metadataProvider = new JdbcMetadataColumnV2Provider(
            required(properties, "metadata.jdbc.url"),
            required(properties, "metadata.jdbc.user"),
            required(properties, "metadata.jdbc.password"),
            get(properties, "metadata.columnTable", "metadata_column_v2"),
            get(properties, "metadata.viewInfoTable", "hive_view_parse_info")
        );

        LineageAnalyzeResult result = new HiveScriptLineageAnalyzer().analyzeScript(
            LineageAnalyzeRequest.builder()
                .workflowId(get(properties, "lineage.workflowId", "wf_local"))
                .taskId(get(properties, "lineage.taskId", taskIdFrom(scriptPath)))
                .taskVersion(get(properties, "lineage.taskVersion", "V1"))
                .clusterName(get(properties, "lineage.clusterName", "default"))
                .defaultDatabase(defaultDatabase)
                .runtimeVariables(runtimeVariables(properties))
                .metadataProvider(metadataProvider)
                .metadataCache(new HiveMetadataCache())
                .rawScript(script)
                .options(options)
                .build()
        );

        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(
            outputPath,
            LineageMarkdownRenderer.render(result, "Hive SQL Lineage Parse Result", defaultDatabase).getBytes(StandardCharsets.UTF_8)
        );

        messageConsumer.accept("Lineage parse finished.");
        messageConsumer.accept("Config: " + configPath.toAbsolutePath());
        messageConsumer.accept("SQL script: " + scriptPath.toAbsolutePath());
        messageConsumer.accept("Output: " + outputPath.toAbsolutePath());
        messageConsumer.accept("Statements: total=" + result.getTotalStatementCount()
            + ", ignored=" + result.getIgnoredStatementCount()
            + ", failed=" + result.getFailedStatementCount());
        return result.getFailedStatementCount() > 0 ? 2 : 0;
    }

    private static Properties loadProperties(Path configPath) throws Exception {
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(configPath)) {
            properties.load(inputStream);
        }
        return properties;
    }

    private static Map<String, String> runtimeVariables(Properties properties) {
        Map<String, String> variables = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("runtimeVariable.")) {
                variables.put(name.substring("runtimeVariable.".length()), resolveValue(properties.getProperty(name)));
            }
        }
        return variables;
    }

    private static String required(Properties properties, String key) {
        String value = get(properties, key, null);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required config: " + key);
        }
        return value;
    }

    private static String get(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : resolveValue(value.trim());
    }

    private static String resolveValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("${env:") && value.endsWith("}")) {
            String envName = value.substring("${env:".length(), value.length() - 1);
            String envValue = System.getenv(envName);
            return envValue == null ? "" : envValue;
        }
        if (value.startsWith("${sys:") && value.endsWith("}")) {
            String propertyName = value.substring("${sys:".length(), value.length() - 1);
            String propertyValue = System.getProperty(propertyName);
            return propertyValue == null ? "" : propertyValue;
        }
        return value;
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(properties, key, String.valueOf(defaultValue)));
    }

    private static int getInt(Properties properties, String key, int defaultValue) {
        return Integer.parseInt(get(properties, key, String.valueOf(defaultValue)));
    }

    private static String taskIdFrom(Path scriptPath) {
        String fileName = scriptPath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
