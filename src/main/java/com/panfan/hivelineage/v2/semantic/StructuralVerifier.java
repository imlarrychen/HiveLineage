package com.panfan.hivelineage.v2.semantic;

import com.panfan.hivelineage.v2.common.DiagnosticBag;
import com.panfan.hivelineage.v2.ir.statement.BoundAnalysisUnit;

public interface StructuralVerifier {
    DiagnosticBag verifyStructure(BoundAnalysisUnit unit);
}
