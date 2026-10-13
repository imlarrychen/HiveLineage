package com.panfan.hivelineage.v2.compare;

import com.panfan.hivelineage.v2.api.SourceDocument;
import com.panfan.hivelineage.v2.api.TaskIdentity;
import com.panfan.hivelineage.v2.common.ModelChecks;

public final class CorpusCase {
    private final String relativePath;
    private final SourceDocument source;
    private final TaskIdentity task;
    private final int expectedFailureCount;

    public CorpusCase(String relativePath, SourceDocument source, TaskIdentity task, int expectedFailureCount) {
        this.relativePath = ModelChecks.text(relativePath, "relativePath");
        this.source = ModelChecks.value(source, "source");
        this.task = ModelChecks.value(task, "task");
        this.expectedFailureCount = ModelChecks.nonNegative(expectedFailureCount, "expectedFailureCount");
    }

    public String getRelativePath() {
        return relativePath;
    }

    public SourceDocument getSource() {
        return source;
    }

    public TaskIdentity getTask() {
        return task;
    }

    public int getExpectedFailureCount() {
        return expectedFailureCount;
    }
}
