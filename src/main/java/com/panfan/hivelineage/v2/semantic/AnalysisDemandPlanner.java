package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.api.AnalysisContract;
import com.panfan.hivelineage.v2.syntax.SyntaxStatement;

public interface AnalysisDemandPlanner {
    BindingDemand plan(SyntaxStatement syntax, AnalysisContract contract);
}
