package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import java.util.List;

public interface LineageRecordExporter {
    List<LineageRecord> export(StatementAnalysisResult statement);
}
