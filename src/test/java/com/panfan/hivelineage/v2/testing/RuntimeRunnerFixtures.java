package com.panfan.hivelineage.v2.testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runtime JUnit fixtures are compiled outside the IDE's test discovery roots. */
final class RuntimeRunnerFixtures implements AutoCloseable {
    private final Path directory;
    private final URLClassLoader loader;
    private final Class<?> fixture;

    private RuntimeRunnerFixtures(Path directory, URLClassLoader loader, Class<?> fixture) {
        this.directory = directory;
        this.loader = loader;
        this.fixture = fixture;
    }

    static RuntimeRunnerFixtures open(String name) throws Exception {
        if (!Arrays.asList("MixedFixture", "UnavailableFixture", "BrokenFixture", "JdbcMetadataFixture").contains(name)) {
            throw new IllegalArgumentException("Unknown runner fixture");
        }
        Path directory = Files.createTempDirectory("lineage-junit-fixture-");
        URLClassLoader loader = null;
        try {
            Path source = directory.resolve(name + ".java");
            try (InputStream template = RuntimeRunnerFixtures.class.getResourceAsStream("/v2/runner-fixtures/" + name + ".java.txt")) {
                if (template == null) {
                    throw new IOException("Runner fixture template is missing: " + name);
                }
                Files.copy(template, source);
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IllegalStateException("Runner statistics tests require a JDK compiler");
            }
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
                boolean compiled = compiler.getTask(null, files, diagnostics,
                    Arrays.asList("-classpath", System.getProperty("java.class.path"), "-d", directory.toString()),
                    null, files.getJavaFileObjects(source.toFile())).call();
                if (!compiled) {
                    throw new IllegalStateException("Runner fixture compilation failed: " + diagnostics.getDiagnostics());
                }
            }
            loader = new URLClassLoader(new URL[] { directory.toUri().toURL() }, RuntimeRunnerFixtures.class.getClassLoader());
            return new RuntimeRunnerFixtures(directory, loader,
                loader.loadClass("com.panfan.hivelineage.v2.testing.runnerfixtures." + name));
        } catch (Exception | Error failure) {
            if (loader != null) {
                loader.close();
            }
            delete(directory);
            throw failure;
        }
    }

    Class<?> getFixture() {
        return fixture;
    }

    Path getClassesDirectory() {
        return directory;
    }

    @Override
    public void close() throws IOException {
        try {
            loader.close();
        } finally {
            delete(directory);
        }
    }

    private static void delete(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }
}
