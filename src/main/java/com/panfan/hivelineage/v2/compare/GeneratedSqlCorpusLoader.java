package com.panfan.hivelineage.v2.compare;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface GeneratedSqlCorpusLoader {
    List<CorpusCase> load(Path directory) throws IOException;
}
