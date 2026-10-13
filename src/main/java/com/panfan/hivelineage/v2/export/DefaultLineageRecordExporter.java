package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.analysis.Dependency;
import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Exports sink contributions with their typed roles; internal Slot edges remain in the full result. */
public final class DefaultLineageRecordExporter implements LineageRecordExporter {
    @Override
    public List<LineageRecord> export(StatementAnalysisResult statement) {
        List<LineageRecord> records = new ArrayList<LineageRecord>();
        if (statement.getLineage().isPresent()) {
            for (Dependency dependency : statement.getLineage().get().getWriteDependencies()) {
                records.add(new LineageRecord(statement.getIdentity(), dependency));
            }
        }
        return Collections.unmodifiableList(records);
    }
}
