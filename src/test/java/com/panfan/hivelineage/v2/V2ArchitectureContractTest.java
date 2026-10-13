package com.panfan.hivelineage.v2;

import com.panfan.hivelineage.v2.analysis.JoinConditionAnalysis;
import com.panfan.hivelineage.v2.analysis.LineageEngine;
import com.panfan.hivelineage.v2.api.V2LineageAnalyzer;
import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;
import com.panfan.hivelineage.v2.lexical.SqlLexer;
import com.panfan.hivelineage.v2.semantic.IrVerifier;
import com.panfan.hivelineage.v2.semantic.SemanticBinder;
import com.panfan.hivelineage.v2.semantic.SinkPlanner;
import com.panfan.hivelineage.v2.syntax.SqlSyntaxParser;
import com.panfan.hivelineage.v2.syntax.SyntaxNormalizer;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

public class V2ArchitectureContractTest {
    private static final Path ROOT = Paths.get("src/main/java/com/panfan/hivelineage/v2");

    @Test
    public void stagesAreInterfacesWithoutFakeRunnableImplementations() {
        List<Class<?>> stages = Arrays.asList(SqlLexer.class, SqlSyntaxParser.class, SyntaxNormalizer.class,
            SemanticBinder.class, SinkPlanner.class, IrVerifier.class, LineageEngine.class,
            JoinConditionAnalysis.class, V2LineageAnalyzer.class);
        for (Class<?> stage : stages) {
            Assert.assertTrue(stage.getName(), stage.isInterface());
            for (Method method : stage.getMethods()) {
                Assert.assertFalse("No default algorithm: " + method, method.isDefault());
            }
        }
    }

    @Test
    public void lineageInputRequiresVerifiedIrInsteadOfSqlOrProvider() {
        for (Class<?> stage : Arrays.asList(LineageEngine.class, JoinConditionAnalysis.class)) {
            Method method = stage.getDeclaredMethods()[0];
            Assert.assertEquals(VerifiedAnalysisUnit.class, method.getParameterTypes()[0]);
            for (Class<?> parameter : method.getParameterTypes()) {
                Assert.assertFalse(parameter.getName(), parameter.getName().contains("Provider"));
                Assert.assertNotEquals(String.class, parameter);
            }
        }
    }

    @Test
    public void typedCoreCannotDependOnHiveOrExistingDto() throws IOException {
        assertNoReferences("ir", Arrays.asList("org.apache.hadoop.hive", "org.antlr",
            "com.panfan.hivelineage.v2.metadata", "com.panfan.hivelineage.v2.syntax",
            "com.panfan.hivelineage.v2.lexical", "com.panfan.hivelineage.result",
            "com.panfan.hivelineage.core"));
        assertNoReferences("analysis", Arrays.asList("org.apache.hadoop.hive", "org.antlr",
            "com.panfan.hivelineage.v2.metadata", "com.panfan.hivelineage.v2.syntax",
            "com.panfan.hivelineage.v2.lexical", "com.panfan.hivelineage.v2.dialect",
            "com.panfan.hivelineage.core", "com.panfan.hivelineage.result"));
    }

    @Test
    public void frontendDoesNotAccessSessionCatalogOrMetadata() throws IOException {
        List<String> forbidden = Arrays.asList("com.panfan.hivelineage.v2.metadata",
            "com.panfan.hivelineage.v2.session", "com.panfan.hivelineage.v2.analysis",
            "com.panfan.hivelineage.core", "com.panfan.hivelineage.result",
            "org.apache.hadoop.hive", "org.antlr");
        for (String segment : Arrays.asList("frontend", "lexical", "syntax")) {
            assertNoReferences(segment, forbidden);
        }
    }

    @Test
    // Concrete stage implementations now have legitimate nullable private lookup helpers.
    // Interface contracts remain declarative; unsupported behavior must be an explicit result.
    public void stageContractsHaveNoPlaceholderImplementationsOrUnsupportedStubs() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                    if (text.contains("throw new UnsupportedOperationException")
                        || text.contains("TODO implement") || (text.contains("public interface ") && text.contains("return null;"))) {
                        violations.add(path.toString());
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        Assert.assertTrue(violations.toString(), violations.isEmpty());
    }

    private void assertNoReferences(String segment, List<String> forbidden) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ROOT.resolve(segment))) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/**")) {
                            continue;
                        }
                        for (String namespace : forbidden) {
                            if (trimmed.contains(namespace)) {
                                violations.add(path + ": " + trimmed);
                            }
                        }
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        Assert.assertTrue(violations.toString(), violations.isEmpty());
    }
}
