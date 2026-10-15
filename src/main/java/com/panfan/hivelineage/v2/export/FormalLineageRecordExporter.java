package com.panfan.hivelineage.v2.export;

import com.panfan.hivelineage.v2.api.StatementAnalysisResult;

import java.util.List;

public interface FormalLineageRecordExporter {
    List<TableLineageRecord> exportTables(StatementAnalysisResult statement);

    List<ColumnLineageRecord> exportColumns(StatementAnalysisResult statement);
}
