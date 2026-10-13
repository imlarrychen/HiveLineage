package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

public interface BindingVerifier {
    DiagnosticBag verifyBinding(BoundAnalysisUnit unit, BindingDemand demand);
}
