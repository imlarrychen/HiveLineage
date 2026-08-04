package com.panfan.hivelineage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

public class JavaSourceSizeTest {
    private static final long MAX_LINES = 1000L;

    @Test
    public void everyJavaSourceFileStaysWithinOneThousandLines() throws IOException {
        List<String> oversized = new ArrayList<>();
        inspect(Paths.get("src/main/java"), oversized);
        inspect(Paths.get("src/test/java"), oversized);
        TestSupport.assertTrue(oversized.isEmpty(), "oversized Java sources: " + oversized);
    }

    private static void inspect(Path root, List<String> oversized) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try (Stream<String> lines = Files.lines(path)) {
                    long count = lines.count();
                    if (count > MAX_LINES) {
                        oversized.add(path + " (" + count + ")");
                    }
                } catch (IOException ex) {
                    throw new IllegalStateException("Cannot inspect " + path, ex);
                }
            });
        }
    }
}
