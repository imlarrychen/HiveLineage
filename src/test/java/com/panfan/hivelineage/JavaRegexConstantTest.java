package com.panfan.hivelineage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

public class JavaRegexConstantTest {
    @Test
    public void productionRegexExpressionsAreDeclaredAsConstants() throws IOException {
        List<String> inlineRegexes = new ArrayList<>();
        inspect(Paths.get("src/main/java"), inlineRegexes);
        TestSupport.assertTrue(inlineRegexes.isEmpty(), "inline regex expressions: " + inlineRegexes);
    }

    private static void inspect(Path root, List<String> inlineRegexes) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> inspectFile(path, inlineRegexes));
        }
    }

    private static void inspectFile(Path path, List<String> inlineRegexes) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int index = 0; index < lines.size(); index++) {
                inspectLine(path, lines, index, inlineRegexes);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot inspect " + path, ex);
        }
    }

    private static void inspectLine(
        Path path,
        List<String> lines,
        int index,
        List<String> inlineRegexes
    ) {
        String line = lines.get(index);
        if (usesInlinePatternCompile(lines, index) || usesInlineStringRegex(line)) {
            inlineRegexes.add(path + ":" + (index + 1));
        }
    }

    private static boolean usesInlinePatternCompile(List<String> lines, int index) {
        String line = lines.get(index);
        if (!line.contains("Pattern.compile(")) {
            return false;
        }
        return !line.contains("static final Pattern")
            && (index == 0 || !lines.get(index - 1).contains("static final Pattern"));
    }

    private static boolean usesInlineStringRegex(String line) {
        return line.contains(".matches(\"")
            || line.contains(".split(\"")
            || line.contains(".replaceAll(\"")
            || line.contains(".replaceFirst(\"");
    }
}
