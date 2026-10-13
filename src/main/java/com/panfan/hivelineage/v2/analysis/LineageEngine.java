package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;

/** Contract only. No SQL, parser or metadata service is an input. */
public interface LineageEngine {
    LineageFacts analyze(VerifiedAnalysisUnit unit, LineageRuleRegistry rules);
}
