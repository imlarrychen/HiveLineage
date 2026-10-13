package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.api.StatementAnalysisResult;
import java.util.List;

public interface JoinConditionRecordExporter {
    List<JoinConditionRecord> export(StatementAnalysisResult statement);
}
