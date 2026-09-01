package com.panfan.hivelineage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

public class JavaImportAndBraceStyleTest {
    private static final Pattern WILDCARD_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?[^;]*\\.\\*;\\s*$");
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([^;]+);\\s*$");
    private static final Pattern INLINE_BLOCK_BODY = Pattern.compile(
        "^\\s*(?:.*\\)\\s*|.*\\belse\\s*)\\{\\s*\\S.*}\\s*$"
    );

    @Test
    public void javaSourcesDoNotUseWildcardImports() throws IOException {
        List<String> violations = new ArrayList<>();
        inspect(Paths.get("src/main/java"), violations, JavaImportAndBraceStyleTest::findWildcardImport);
        inspect(Paths.get("src/test/java"), violations, JavaImportAndBraceStyleTest::findWildcardImport);
        TestSupport.assertTrue(violations.isEmpty(), "wildcard imports: " + violations);
    }

    @Test
    public void javaSourcesDoNotKeepUnusedImports() throws IOException {
        List<String> violations = new ArrayList<>();
        inspect(Paths.get("src/main/java"), violations, JavaImportAndBraceStyleTest::findUnusedImport);
        inspect(Paths.get("src/test/java"), violations, JavaImportAndBraceStyleTest::findUnusedImport);
        TestSupport.assertTrue(violations.isEmpty(), "unused imports: " + violations);
    }

    @Test
    public void javaSourcesPutBlockBodyOnSeparateLines() throws IOException {
        List<String> violations = new ArrayList<>();
        inspect(Paths.get("src/main/java"), violations, JavaImportAndBraceStyleTest::findInlineBlockBody);
        inspect(Paths.get("src/test/java"), violations, JavaImportAndBraceStyleTest::findInlineBlockBody);
        TestSupport.assertTrue(violations.isEmpty(), "inline block bodies: " + violations);
    }

    private static void inspect(Path root, List<String> violations, LineViolationFinder finder) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> inspectFile(path, violations, finder));
        }
    }

    private static void inspect(Path root, List<String> violations, FileViolationFinder finder) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> finder.findViolations(path, violations));
        }
    }

    private static void inspectFile(Path path, List<String> violations, LineViolationFinder finder) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int index = 0; index < lines.size(); index++) {
                if (finder.isViolation(lines.get(index))) {
                    violations.add(path + ":" + (index + 1));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect " + path, ex);
        }
    }

    private static void findUnusedImport(Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            String sourceWithoutImports = sourceWithoutImports(lines);
            for (int index = 0; index < lines.size(); index++) {
                java.util.regex.Matcher matcher = IMPORT.matcher(lines.get(index));
                if (matcher.matches() && !usesImportedName(sourceWithoutImports, matcher.group(1))) {
                    violations.add(path + ":" + (index + 1));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect " + path, ex);
        }
    }

    private static String sourceWithoutImports(List<String> lines) {
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            if (!IMPORT.matcher(line).matches()) {
                source.append(line).append('\n');
            }
        }
        return source.toString();
    }

    private static boolean usesImportedName(String sourceWithoutImports, String importedName) {
        String simpleName = importedName.substring(importedName.lastIndexOf('.') + 1);
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(sourceWithoutImports).find();
    }

    private static boolean findWildcardImport(String line) {
        return WILDCARD_IMPORT.matcher(line).matches();
    }

    private static boolean findInlineBlockBody(String line) {
        return INLINE_BLOCK_BODY.matcher(line).matches()
            && !line.trim().startsWith("static {");
    }

    private interface LineViolationFinder {
        boolean isViolation(String line);
    }

    private interface FileViolationFinder {
        void findViolations(Path path, List<String> violations);
    }
}
