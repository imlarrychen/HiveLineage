package com.panfan.hivelineage.v2.testing;

import com.panfan.hivelineage.request.LineageParseMode;
import com.panfan.hivelineage.v2.export.LineageJsonRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Captures full native JSON in deterministic corpus/mode order for before/after comparison. */
public final class NativeCorpusSnapshot {
    private NativeCorpusSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one snapshot output directory");
        }
        Path output = Paths.get(args[0]);
        Files.createDirectories(output);
        List<Path> cases;
        try (Stream<Path> files = Files.list(Paths.get("src/test/resources/hql/generated"))) {
            cases = files.filter(path -> path.getFileName().toString().endsWith(".sql")).sorted().collect(Collectors.toList());
        }
        int count = 0;
        for (Path path : cases) {
            for (LineageParseMode mode : LineageParseMode.values()) {
                String name = path.getFileName().toString();
                Files.write(output.resolve(name + "." + mode + ".json"), LineageJsonRenderer.render(GeneratedSqlSupport.analyze(name, mode)).getBytes(StandardCharsets.UTF_8));
                count++;
            }
        }
        System.out.println("Native JSON snapshots=" + count);
    }
}
