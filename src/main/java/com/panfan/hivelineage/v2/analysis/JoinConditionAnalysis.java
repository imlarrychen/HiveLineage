package com.panfan.hivelineage.v2.analysis;

import com.panfan.hivelineage.v2.ir.statement.VerifiedAnalysisUnit;

public interface JoinConditionAnalysis {
    JoinFacts analyze(VerifiedAnalysisUnit unit, LineageRuleRegistry rules);
}
